package com.itangcent.idea.plugin.api.export.core

import com.google.inject.Inject
import com.intellij.psi.*
import com.itangcent.common.constant.Attrs
import com.itangcent.common.constant.HttpMethod
import com.itangcent.common.exception.ProcessCanceledException
import com.itangcent.common.kit.KVUtils
import com.itangcent.common.logger.traceError
import com.itangcent.common.model.Request
import com.itangcent.common.model.Response
import com.itangcent.common.model.hasBodyOrForm
import com.itangcent.common.utils.*
import com.itangcent.http.RequestUtils
import com.itangcent.idea.plugin.api.ClassApiExporterHelper
import com.itangcent.idea.plugin.api.MethodInferHelper
import com.itangcent.idea.plugin.api.export.condition.ConditionOnDoc
import com.itangcent.idea.plugin.api.export.rule.RequestRuleWrap
import com.itangcent.idea.plugin.api.export.spring.SpringClassName
import com.itangcent.idea.plugin.settings.helper.IntelligentSettingsHelper
import com.itangcent.idea.psi.PsiMethodResource
import com.itangcent.idea.psi.PsiMethodSet
import com.itangcent.intellij.config.rule.RuleComputer
import com.itangcent.intellij.config.rule.computer
import com.itangcent.intellij.context.ActionContext
import com.itangcent.intellij.extend.takeIfNotOriginal
import com.itangcent.intellij.extend.unbox
import com.itangcent.intellij.extend.withBoundary
import com.itangcent.intellij.jvm.*
import com.itangcent.intellij.jvm.duck.DuckType
import com.itangcent.intellij.jvm.duck.SingleDuckType
import com.itangcent.intellij.jvm.element.ExplicitElement
import com.itangcent.intellij.jvm.element.ExplicitMethod
import com.itangcent.intellij.logger.Logger
import com.itangcent.intellij.psi.ContextSwitchListener
import com.itangcent.intellij.psi.PsiClassUtils
import com.itangcent.intellij.util.*
import kotlin.reflect.KClass


/**
 * An abstract implementation of  [ClassExporter]
 * that exports [Request] from code.
 */
@ConditionOnDoc("request")
abstract class RequestClassExporter : ClassExporter {

    @Inject
    protected val cacheAble: CacheAble? = null

    @Inject
    protected lateinit var additionalParseHelper: AdditionalParseHelper

    override fun support(docType: KClass<*>): Boolean {
        return docType == Request::class
    }

    @Inject
    protected lateinit var logger: Logger

    @Inject
    private val docHelper: DocHelper? = null

    @Inject
    protected val psiClassHelper: PsiClassHelper? = null

    @Inject
    protected lateinit var requestBuilderListener: RequestBuilderListener

    @Inject
    protected lateinit var intelligentSettingsHelper: IntelligentSettingsHelper

    @Inject
    protected lateinit var duckTypeHelper: DuckTypeHelper

    @Inject
    protected val methodReturnInferHelper: MethodInferHelper? = null

    @Inject
    protected lateinit var ruleComputer: RuleComputer

    @Inject
    protected val jvmClassHelper: JvmClassHelper? = null

    @Inject(optional = true)
    protected val methodFilter: MethodFilter? = null

    @Inject
    protected lateinit var actionContext: ActionContext

    @Inject
    protected var apiHelper: ApiHelper? = null

    @Inject
    private val linkExtractor: LinkExtractor? = null

    @Inject
    private val linkResolver: LinkResolver? = null

    @Inject
    private val contextSwitchListener: ContextSwitchListener? = null

    @Inject
    private lateinit var classApiExporterHelper: ClassApiExporterHelper

    @Inject
    protected lateinit var commentResolver: CommentResolver

    override fun export(cls: Any, docHandle: DocHandle): Boolean {
        if (cls !is PsiClass) {
            return false
        }
        return doExport(cls, docHandle)
    }

    private fun doExport(cls: PsiClass, docHandle: DocHandle): Boolean {

        contextSwitchListener?.switchTo(cls)

        actionContext.checkStatus()
        val clsQualifiedName = actionContext.callInReadUI { cls.qualifiedName }
        when {
            !hasApi(cls) -> {
                return false
            }
            shouldIgnore(cls) -> {
                logger.info("ignore class:$clsQualifiedName")
                return true
            }
        }

        logger.info("search api from: $clsQualifiedName")

        val classExportContext = ClassExportContext(cls)

        ruleComputer.computer(ClassExportRuleKeys.API_CLASS_PARSE_BEFORE, cls)

        try {
            processClass(cls, classExportContext)
            actionContext.withBoundary {
                val psiMethodSet = PsiMethodSet()
                classApiExporterHelper.foreachMethod(cls) { explicitMethod ->
                    val method = explicitMethod.psi()
                    if (isApi(method)
                        && methodFilter?.checkMethod(method) != false
                        && psiMethodSet.add(method)
                    ) {
                        try {
                            ruleComputer.computer(ClassExportRuleKeys.API_METHOD_PARSE_BEFORE, explicitMethod)
                            exportMethodApi(cls, explicitMethod, classExportContext, docHandle)
                        } catch (e: Exception) {
                            logger.traceError("error to export api from method:" + method.name, e)
                        } finally {
                            ruleComputer.computer(ClassExportRuleKeys.API_METHOD_PARSE_AFTER, explicitMethod)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            logger.traceError("error to export api from class:" + cls.name, e)
        } finally {
            ruleComputer.computer(ClassExportRuleKeys.API_CLASS_PARSE_AFTER, cls)
        }
        return true
    }

    protected abstract fun processClass(cls: PsiClass, classExportContext: ClassExportContext)

    protected abstract fun hasApi(psiClass: PsiClass): Boolean

    protected abstract fun isApi(psiMethod: PsiMethod): Boolean

    protected open fun shouldIgnore(psiElement: PsiElement): Boolean {
        return ruleComputer.computer(ClassExportRuleKeys.IGNORE, psiElement) ?: false
    }

    private fun exportMethodApi(
        psiClass: PsiClass, method: ExplicitMethod,
        classExportContext: ClassExportContext,
        docHandle: DocHandle,
    ) {

        actionContext.checkStatus()

        val request = Request()

        request.resource = PsiMethodResource(method.psi(), psiClass)

        val methodExportContext = MethodExportContext(classExportContext, method)

        requestBuilderListener.startProcessMethod(methodExportContext, request)

        processMethod(methodExportContext, request)

        processMethodParameters(methodExportContext, request)

        processResponse(methodExportContext, request)

        processCompleted(methodExportContext, request)

        requestBuilderListener.processCompleted(methodExportContext, request)

        docHandle(request)
    }

    protected open fun processMethod(
        methodExportContext: MethodExportContext,
        request: Request,
    ) {

        apiHelper!!.nameAndAttrOfApi(methodExportContext.element(), {
            requestBuilderListener.setName(methodExportContext, request, it)
        }, {
            requestBuilderListener.appendDesc(methodExportContext, request, it)
        })

        //computer content-type.
        ruleComputer.computer(ClassExportRuleKeys.METHOD_CONTENT_TYPE, methodExportContext.element())
            ?.let {
                requestBuilderListener.setContentType(methodExportContext, request, it)
            }

    }

    protected open fun readParamDoc(explicitParameter: ExplicitElement<*>): String? {
        return ruleComputer.computer(ClassExportRuleKeys.PARAM_DOC, explicitParameter)
    }

    protected open fun readParamDefaultValue(param: ExplicitElement<*>): String? {
        return ruleComputer.computer(ClassExportRuleKeys.PARAM_DEFAULT_VALUE, param)
    }

    protected open fun processCompleted(methodExportContext: MethodExportContext, request: Request) {
        //parse additionalHeader by config
        val additionalHeader = ruleComputer.computer(
            ClassExportRuleKeys.METHOD_ADDITIONAL_HEADER,
            methodExportContext.element()
        )
        if (additionalHeader.notNullOrEmpty()) {
            val additionalHeaders = additionalHeader!!.lines()
            for (headerStr in additionalHeaders) {
                cacheAble!!.cache("header" to headerStr) {
                    val header = safe { additionalParseHelper.parseHeaderFromJson(headerStr) }
                    when {
                        header == null -> {
                            logger.error("error to parse additional header: $headerStr")
                            return@cache null
                        }
                        header.name.isNullOrBlank() -> {
                            logger.error("no name had be found in: $headerStr")
                            return@cache null
                        }
                        else -> return@cache header
                    }
                }?.let {
                    requestBuilderListener.addHeader(methodExportContext, request, it)
                }
            }
        }

        //parse additionalParam by config
        val additionalParam =
            ruleComputer.computer(ClassExportRuleKeys.METHOD_ADDITIONAL_PARAM, methodExportContext.element())
        if (additionalParam.notNullOrEmpty()) {
            val additionalParams = additionalParam!!.lines()
            for (paramStr in additionalParams) {
                cacheAble!!.cache("param" to paramStr) {
                    val param = safe { additionalParseHelper.parseParamFromJson(paramStr) }
                    when {
                        param == null -> {
                            logger.error("error to parse additional param: $paramStr")
                            return@cache null
                        }
                        param.name.isNullOrBlank() -> {
                            logger.error("no name had be found in: $paramStr")
                            return@cache null
                        }
                        else -> return@cache param
                    }
                }?.let {
                    requestBuilderListener.addParam(methodExportContext, request, it)
                }
            }
        }

        //parse additionalResponseHeader by config
        if (request.response.notNullOrEmpty()) {
            val additionalResponseHeader =
                ruleComputer.computer(
                    ClassExportRuleKeys.METHOD_ADDITIONAL_RESPONSE_HEADER,
                    methodExportContext.element()
                )
            if (additionalResponseHeader.notNullOrEmpty()) {
                val additionalHeaders = additionalResponseHeader!!.lines()
                for (headerStr in additionalHeaders) {
                    cacheAble!!.cache("header" to headerStr) {
                        val header = safe { additionalParseHelper.parseHeaderFromJson(headerStr) }
                        when {
                            header == null -> {
                                logger.error("error to parse additional response header: $headerStr")
                                return@cache null
                            }
                            header.name.isNullOrBlank() -> {
                                logger.error("no name had be found in: $headerStr")
                                return@cache null
                            }
                            else -> return@cache header
                        }
                    }?.let {
                        request.response!!.forEach { response ->
                            requestBuilderListener.addResponseHeader(
                                methodExportContext,
                                response, it
                            )
                        }
                    }
                }
            }
        }

        //fire AFTER_EXPORT
        ruleComputer.computer(ClassExportRuleKeys.AFTER_EXPORT, methodExportContext.element()) {
            it.setExt("api", RequestRuleWrap(methodExportContext, request))
        }
    }

    protected open fun processResponse(methodExportContext: MethodExportContext, request: Request) {

        var returnType: DuckType? = null
        var fromRule = false
        val returnTypeByRule = ruleComputer.computer(ClassExportRuleKeys.METHOD_RETURN, methodExportContext.element())
        if (returnTypeByRule.notNullOrBlank()) {
            val resolvedReturnType = duckTypeHelper.resolve(returnTypeByRule!!.trim(), methodExportContext.psi())
            if (resolvedReturnType != null) {
                returnType = resolvedReturnType
                fromRule = true
            }
        }
        if (!fromRule) {
            returnType = methodExportContext.type()
        }

        if (returnType != null) {
            try {
                val response = Response()

                requestBuilderListener.setResponseCode(
                    methodExportContext,
                    response, 200
                )

                val ultimateComment = StringBuilder()

                val typedResponse = parseResponseBody(returnType, fromRule, methodExportContext.element())

                val descOfReturn = docHelper!!.findDocByTag(methodExportContext.psi(), "return")

                val methodReturnMain =
                    ruleComputer.computer(ClassExportRuleKeys.METHOD_RETURN_MAIN, methodExportContext.element())

                var context: PsiElement = methodExportContext.psi()
                val methodReturnMainType: DuckType?
                if (methodReturnMain.isNullOrBlank()) {
                    methodReturnMainType = methodExportContext.type()
                } else {
                    val explicitReturnType = duckTypeHelper.explicit(returnType.unbox() as SingleDuckType)
                    val explicitField = explicitReturnType.fields().firstOrNull { it.name() == methodReturnMain }
                    methodReturnMainType = explicitField?.getType()
                    methodReturnMainType.unbox().cast(SingleDuckType::class)
                        ?.psiClass()?.let {
                            context = it
                        }
                }

                if (descOfReturn.notNullOrBlank()) {

                    val options: ArrayList<HashMap<String, Any?>> = ArrayList()
                    val comment = linkExtractor!!.extract(
                        descOfReturn,
                        context,
                        object : AbstractLinkResolve() {

                            override fun linkToPsiElement(plainText: String, linkTo: Any?): String? {

                                psiClassHelper!!.resolveEnumOrStatic(plainText, context, "")
                                    ?.let { options.addAll(it) }

                                return super.linkToPsiElement(plainText, linkTo)
                            }

                            override fun linkToType(plainText: String, linkType: PsiType): String? {
                                return jvmClassHelper!!.resolveClassInType(linkType)?.let {
                                    linkResolver!!.linkToClass(it)
                                }
                            }

                            override fun linkToClass(plainText: String, linkClass: PsiClass): String? {
                                return linkResolver!!.linkToClass(linkClass)
                            }

                            override fun linkToField(plainText: String, linkField: PsiField): String? {
                                return linkResolver!!.linkToProperty(linkField)
                            }

                            override fun linkToMethod(plainText: String, linkMethod: PsiMethod): String? {
                                return linkResolver!!.linkToMethod(linkMethod)
                            }

                            override fun linkToUnresolved(plainText: String): String {
                                return plainText
                            }
                        })

                    if (comment != null) {
                        ultimateComment.appendLine(comment)
                    } else {
                        ultimateComment.appendLine(descOfReturn)
                    }

                    if (options.notNullOrEmpty()) {
                        ultimateComment.appendLine(KVUtils.getOptionDesc(options))
                    }
                }

                if (methodReturnMainType != null) {
                    commentResolver.resolveCommentForType(methodReturnMainType, methodExportContext.psi())?.let {
                        ultimateComment.appendLine(it)
                    }
                }

                val ultimateCommentStr = ultimateComment.toString().trimEnd()
                if (ultimateCommentStr.isNotEmpty()) {
                    if (methodReturnMain.isNullOrBlank()) {
                        requestBuilderListener.appendResponseBodyDesc(
                            methodExportContext,
                            response, ultimateCommentStr
                        )
                    } else {
                        if (!KVUtils.addKeyComment(typedResponse, methodReturnMain, ultimateCommentStr)) {
                            requestBuilderListener.appendResponseBodyDesc(methodExportContext,
                                response,
                                ultimateCommentStr)
                        }
                    }
                }

                requestBuilderListener.setResponseBody(methodExportContext, response, "raw", typedResponse)

                requestBuilderListener.addResponseHeader(
                    methodExportContext,
                    response,
                    "content-type",
                    "application/json;charset=UTF-8"
                )

                requestBuilderListener.addResponse(methodExportContext, request, response)

            } catch (e: ProcessCanceledException) {
                //ignore cancel
            } catch (e: Throwable) {
                logger.traceError("error to parse body", e)

            }
        }
    }

    /**
     * unbox queryParam
     */
    protected fun tinyQueryParam(paramVal: Any?): Any? = paramVal.unbox()

    @Deprecated(message = "will be removed soon")
    protected open fun findAttrOfMethod(method: PsiMethod): String? {
        return docHelper!!.getAttrOfDocComment(method)
    }

    private fun processMethodParameters(methodExportContext: MethodExportContext, request: Request) {

        val params = methodExportContext.element().getParameters()

        if (params.isNotEmpty()) {

            val paramDocComment = classApiExporterHelper.extractParamComment(methodExportContext.psi())

            val parsedParams: ArrayList<ParameterExportContext> = ArrayList()

            for (param in params) {
                if (ruleComputer.computer(ClassExportRuleKeys.PARAM_IGNORE, param) == true) {
                    continue
                }

                ruleComputer.computer(ClassExportRuleKeys.API_PARAM_BEFORE, param)

                try {
                    val paramType = param.getType() ?: continue
                    val unboxType = paramType.unbox()

                    if (jvmClassHelper!!.isInheritor(unboxType, *SpringClassName.SPRING_REQUEST_RESPONSE)) {
                        //ignore @HttpServletRequest and @HttpServletResponse
                        continue
                    }

                    parsedParams.add(ParameterExportContext(methodExportContext, param).also { it.raw() })
                } finally {
                    ruleComputer.computer(ClassExportRuleKeys.API_PARAM_AFTER, param)
                }
            }

            val hasFile = parsedParams.any { it.raw().hasFile() }

            if (hasFile) {
                if (request.method == HttpMethod.GET) {
                    logger.warn("file param in `GET` API [${request.path}]")
                } else if (request.method == null || request.method == HttpMethod.NO_METHOD) {
                    request.method = ruleComputer.computer(
                        ClassExportRuleKeys.METHOD_DEFAULT_HTTP_METHOD,
                        methodExportContext.element()
                    ) ?: HttpMethod.POST
                }
                requestBuilderListener.addHeader(
                    methodExportContext,
                    request, "Content-Type", "multipart/form-data"
                )
            }

            for (parameterExportContext in parsedParams) {
                ruleComputer.computer(ClassExportRuleKeys.API_PARAM_BEFORE, parameterExportContext.element())

                try {
                    processMethodParameter(
                        request,
                        parameterExportContext,
                        KVUtils.getUltimateComment(paramDocComment, parameterExportContext.name())
                            .append(readParamDoc(parameterExportContext.element()))
                    )
                } finally {
                    ruleComputer.computer(ClassExportRuleKeys.API_PARAM_AFTER, parameterExportContext.element())
                }
            }
        }

        if (request.method == null || request.method == HttpMethod.NO_METHOD) {
            val defaultHttpMethod = ruleComputer.computer(
                ClassExportRuleKeys.METHOD_DEFAULT_HTTP_METHOD,
                methodExportContext.element()
            )
            requestBuilderListener.setMethod(
                methodExportContext,
                request, defaultHttpMethod ?: HttpMethod.GET
            )
        }

        if (request.hasBodyOrForm()) {
            requestBuilderListener.addHeaderIfMissed(
                methodExportContext,
                request, "Content-Type", "application/x-www-form-urlencoded"
            )
        }

    }

    abstract fun processMethodParameter(
        request: Request,
        parameterExportContext: ParameterExportContext,
        paramDesc: String?,
    )

    protected fun setRequestBody(
        exportContext: ExportContext,
        request: Request, typeObject: Any?, paramDesc: String?,
    ) {
        requestBuilderListener.setMethodIfMissed(exportContext, request, HttpMethod.POST)
        requestBuilderListener.addHeader(exportContext, request, "Content-Type", "application/json")
        requestBuilderListener.setJsonBody(
            exportContext,
            request,
            typeObject,
            paramDesc
        )
        return
    }

    @Suppress("UNCHECKED_CAST")
    protected open fun addParamAsQuery(
        parameterExportContext: VariableExportContext,
        request: Request, typeObject: Any?, paramDesc: String? = null,
    ) {
        try {
            parameterExportContext.setExt("paramTypeObject", typeObject)

            if (typeObject == Magics.FILE_STR) {
                requestBuilderListener.addFormFileParam(
                    parameterExportContext,
                    request, parameterExportContext.paramName(),
                    parameterExportContext.required()
                        ?: ruleComputer.computer(ClassExportRuleKeys.PARAM_REQUIRED, parameterExportContext.element())
                        ?: false, paramDesc
                )
                return
            }

            if (typeObject == null || typeObject !is Map<*, *>) {
                requestBuilderListener.addParam(
                    parameterExportContext,
                    request,
                    parameterExportContext.paramName(),
                    tinyQueryParam(parameterExportContext.defaultVal() ?: typeObject),
                    parameterExportContext.required()
                        ?: ruleComputer.computer(ClassExportRuleKeys.PARAM_REQUIRED, parameterExportContext.element())
                        ?: false,
                    paramDesc
                )
                return
            }

            if (request.hasBodyOrForm()
                && request.formParams.isNullOrEmpty()
                && typeObject.isComplex()
            ) {
                requestBuilderListener.setMethodIfMissed(parameterExportContext, request, HttpMethod.POST)
                addParamAsForm(parameterExportContext, request, typeObject, paramDesc)
                return
            }

            if (this.intelligentSettingsHelper.queryExpanded()) {
                typeObject.flatValid(object : FieldConsumer {
                    override fun consume(parent: Map<*, *>?, path: String, key: String, value: Any?) {
                        parameterExportContext.setExt("parent", parent)
                        parameterExportContext.setExt("key", key)
                        parameterExportContext.setExt("path", path)
                        val fv = deepComponent(value)
                        if (fv == Magics.FILE_STR) {
                            logger.warn("confused file param [$path] for [GET]")
                        }
                        requestBuilderListener.addParam(
                            parameterExportContext,
                            request,
                            path,
                            tinyQueryParam(
                                parent?.getAs<Boolean>(Attrs.DEFAULT_VALUE_ATTR, key)
                                    ?: value
                            ),
                            parent?.getAs<Boolean>(Attrs.REQUIRED_ATTR, key) ?: false,
                            KVUtils.getUltimateComment(parent?.getAs(Attrs.COMMENT_ATTR), key)
                        )
                    }
                })
            } else {
                val fields = typeObject.asKV()
                val comment = fields.getAsKv(Attrs.COMMENT_ATTR)
                val required = fields.getAsKv(Attrs.REQUIRED_ATTR)
                val defaultVal = fields.getAsKv(Attrs.DEFAULT_VALUE_ATTR)
                parameterExportContext.setExt("parent", fields)
                fields.forEachValid { filedName, fieldVal ->
                    parameterExportContext.setExt("key", filedName)
                    val fv = deepComponent(defaultVal?.get(filedName) ?: fieldVal)
                    if (fv == Magics.FILE_STR && request.method == HttpMethod.GET) {
                        logger.warn("try upload file at `GET:`${request.path}")
                    }
                    requestBuilderListener.addParam(
                        parameterExportContext,
                        request, filedName, tinyQueryParam(fv),
                        required?.getAs(filedName) ?: false,
                        KVUtils.getUltimateComment(comment, filedName)
                    )
                }
            }
        } catch (e: Exception) {
            logger.traceError(
                "error to parse [${
                    parameterExportContext.type()?.canonicalText()
                }] as Queries", e
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    protected open fun addParamAsForm(
        parameterExportContext: VariableExportContext,
        request: Request, typeObject: Any?, paramDesc: String? = null,
    ) {

        try {
            if (typeObject == Magics.FILE_STR) {
                requestBuilderListener.addFormFileParam(
                    parameterExportContext,
                    request, parameterExportContext.paramName(),
                    ruleComputer.computer(
                        ClassExportRuleKeys.PARAM_REQUIRED,
                        parameterExportContext.element()
                    )
                        ?: false, paramDesc
                )
                return
            }

            if (typeObject != null && typeObject is Map<*, *>) {
                requestBuilderListener.addHeaderIfMissed(
                    parameterExportContext,
                    request, "Content-Type", "multipart/form-data"
                )
                if (this.intelligentSettingsHelper.formExpanded()) {
                    typeObject.flatValid(object : FieldConsumer {
                        override fun consume(parent: Map<*, *>?, path: String, key: String, value: Any?) {
                            parameterExportContext.setExt("parent", parent)
                            parameterExportContext.setExt("key", key)
                            parameterExportContext.setExt("path", path)
                            val fv = deepComponent(value)
                            if (fv == Magics.FILE_STR) {
                                requestBuilderListener.addFormFileParam(
                                    parameterExportContext,
                                    request, path,
                                    parent?.getAs<Boolean>(Attrs.REQUIRED_ATTR, key) ?: false,
                                    KVUtils.getUltimateComment(parent?.getAs(Attrs.COMMENT_ATTR), key)
                                )
                            } else {
                                requestBuilderListener.addFormParam(
                                    parameterExportContext,
                                    request, path,
                                    tinyQueryParam(
                                        (parent?.getAs<Boolean>(Attrs.DEFAULT_VALUE_ATTR, key)
                                            ?: value.takeIfNotOriginal())
                                    )?.toString(),
                                    parent?.getAs<Boolean>(Attrs.REQUIRED_ATTR, key) ?: false,
                                    KVUtils.getUltimateComment(parent?.getAs(Attrs.COMMENT_ATTR), key)
                                )
                            }
                        }
                    })
                } else {
                    val fields = typeObject.asKV()
                    val comment = fields.getAsKv(Attrs.COMMENT_ATTR)
                    val required = fields.getAsKv(Attrs.REQUIRED_ATTR)
                    val defaultVal = fields.getAsKv(Attrs.DEFAULT_VALUE_ATTR)
                    requestBuilderListener.addHeaderIfMissed(
                        parameterExportContext,
                        request, "Content-Type", "application/x-www-form-urlencoded"
                    )
                    parameterExportContext.setExt("parent", fields)
                    fields.forEachValid { filedName, fieldVal ->
                        parameterExportContext.setExt("key", filedName)
                        val fv = deepComponent(defaultVal?.get(filedName) ?: fieldVal)
                        if (fv == Magics.FILE_STR) {
                            requestBuilderListener.addFormFileParam(
                                parameterExportContext,
                                request, filedName,
                                required?.getAs(filedName) ?: false,
                                KVUtils.getUltimateComment(comment, filedName)
                            )
                        } else {
                            requestBuilderListener.addFormParam(
                                parameterExportContext,
                                request, filedName, fv?.takeIfNotOriginal()?.toString(),
                                required?.getAs(filedName) ?: false,
                                KVUtils.getUltimateComment(comment, filedName)
                            )
                        }
                    }
                }
            } else {
                requestBuilderListener.addFormParam(
                    parameterExportContext,
                    request, parameterExportContext.paramName(), tinyQueryParam(typeObject)?.toString(),
                    parameterExportContext.required()
                        ?: ruleComputer.computer(ClassExportRuleKeys.PARAM_REQUIRED, parameterExportContext.element())
                        ?: false, paramDesc
                )
            }
        } catch (e: Exception) {
            logger.traceError(
                "error to parse[" + parameterExportContext.type()?.canonicalText() + "] as ModelAttribute",
                e
            )
        }

        return
    }

    protected fun parseResponseBody(duckType: DuckType?, fromRule: Boolean, method: ExplicitMethod): Any? {

        if (duckType == null) {
            return null
        }

        return when {
            fromRule -> psiClassHelper!!.getTypeObject(
                duckType, method.psi(),
                this.intelligentSettingsHelper.jsonOptionForOutput(JsonOption.READ_COMMENT)
            )
            this.intelligentSettingsHelper.inferEnable() && !duckTypeHelper.isQualified(duckType)
            -> {
                logger.info("try infer return type of method[" + PsiClassUtils.fullNameOfMethod(method.psi()) + "]")
                methodReturnInferHelper!!.inferReturn(method.psi())
//                actionContext!!.callWithTimeout(20000) { methodReturnInferHelper.inferReturn(method) }
            }
            else -> psiClassHelper!!.getTypeObject(
                duckType, method.psi(),
                this.intelligentSettingsHelper.jsonOptionForOutput(JsonOption.READ_COMMENT)
            )
        }
    }

    protected fun deepComponent(obj: Any?): Any? {
        if (obj == null) {
            return null
        }
        if (obj is Array<*>) {
            if (obj.isEmpty()) return obj
            return deepComponent(obj[0])
        }
        if (obj is Collection<*>) {
            if (obj.isEmpty()) return obj
            return deepComponent(obj.first())
        }
        if (obj is Map<*, *>) {
            return RequestUtils.parseRawBody(obj)
        }
        return obj
    }

    //region extent of ParameterExportContext
    fun VariableExportContext.setParamName(name: String) {
        this.setExt("param_name", name)
    }

    fun VariableExportContext.paramName(): String {
        return this.getExt<String>("param_name") ?: this.name()
    }

    fun ExportContext.setRequired(name: Boolean) {
        this.setExt("required", name)
    }

    fun ExportContext.required(): Boolean? {
        return this.getExt<Boolean>("required")
    }

    fun ExportContext.setDefaultVal(defaultVal: String) {
        this.setExt("defaultVal", defaultVal)
    }

    fun ExportContext.defaultVal(): String? {
        return this.getExt<String>("defaultVal")
    }

    fun VariableExportContext.raw(): Any? {
        return this.cache("raw") {
            val paramType = this.type() ?: return@cache null
            val typeObject = psiClassHelper!!.getTypeObject(
                paramType, this.psi(),
                this@RequestClassExporter.intelligentSettingsHelper.jsonOptionForInput(JsonOption.READ_COMMENT)
            )
            this.setExt("raw", typeObject)
            return@cache typeObject
        }
    }

    fun VariableExportContext.unbox(): Any? {
        return this.cache("unbox") {
            return@cache raw().unbox()
        }
    }

    //endregion
}
