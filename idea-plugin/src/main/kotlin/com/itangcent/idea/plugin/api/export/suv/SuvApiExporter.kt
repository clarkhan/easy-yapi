package com.itangcent.idea.plugin.api.export.suv

import com.google.inject.Inject
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.util.containers.ContainerUtil
import com.itangcent.common.exporter.ClassExporter
import com.itangcent.common.exporter.RequestHelper
import com.itangcent.common.model.Request
import com.itangcent.common.utils.GsonUtils
import com.itangcent.idea.plugin.Worker
import com.itangcent.idea.plugin.api.cache.DefaultFileApiCacheRepository
import com.itangcent.idea.plugin.api.cache.FileApiCacheRepository
import com.itangcent.idea.plugin.api.cache.ProjectCacheRepository
import com.itangcent.idea.plugin.api.export.EasyApiConfigReader
import com.itangcent.idea.plugin.api.export.DefaultRequestHelper
import com.itangcent.idea.plugin.api.export.MethodFilter
import com.itangcent.idea.plugin.api.export.SpringClassExporter
import com.itangcent.idea.plugin.api.export.markdown.MarkdownFormatter
import com.itangcent.idea.plugin.api.export.postman.PostmanApiHelper
import com.itangcent.idea.plugin.api.export.postman.PostmanCachedApiHelper
import com.itangcent.idea.plugin.api.export.postman.PostmanConfigReader
import com.itangcent.idea.plugin.api.export.postman.PostmanFormatter
import com.itangcent.idea.plugin.api.export.yapi.*
import com.itangcent.idea.plugin.config.RecommendConfigReader
import com.itangcent.idea.plugin.dialog.SuvApiExportDialog
import com.itangcent.idea.plugin.rule.SuvRuleParser
import com.itangcent.idea.plugin.settings.SettingBinder
import com.itangcent.idea.utils.CustomizedPsiClassHelper
import com.itangcent.idea.utils.FileSaveHelper
import com.itangcent.intellij.config.ConfigReader
import com.itangcent.intellij.config.rule.RuleParser
import com.itangcent.intellij.constant.EventKey
import com.itangcent.intellij.context.ActionContext
import com.itangcent.intellij.extend.guice.singleton
import com.itangcent.intellij.extend.guice.with
import com.itangcent.intellij.file.DefaultLocalFileRepository
import com.itangcent.intellij.file.LocalFileRepository
import com.itangcent.intellij.logger.Logger
import com.itangcent.intellij.psi.PsiClassHelper
import com.itangcent.intellij.psi.SelectedHelper
import com.itangcent.intellij.util.UIUtils
import com.itangcent.intellij.util.traceError
import com.itangcent.suv.http.ConfigurableHttpClientProvider
import com.itangcent.suv.http.HttpClientProvider
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.exception.ExceptionUtils
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.full.createInstance
import kotlin.streams.toList

class SuvApiExporter {

    @Inject
    private val logger: Logger? = null

    @Inject
    private val actionContext: ActionContext? = null

    @Inject
    private val classExporter: ClassExporter? = null

    @Inject
    private val requestHelper: RequestHelper? = null

    @Suppress("UNCHECKED_CAST")
    fun showExportWindow() {

        logger!!.info("Start find apis...")
        val requests: MutableList<Request> = Collections.synchronizedList(ArrayList<Request>())

        SelectedHelper.Builder()
                .classHandle {
                    actionContext!!.checkStatus()
                    classExporter!!.export(it, requestHelper!!) { request ->
                        requests.add(request)
                    }
                }
                .onCompleted {
                    try {
                        if (classExporter is Worker) {
                            classExporter.waitCompleted()
                        }
                        if (requests.isEmpty()) {
                            logger.info("No api be found!")
                            return@onCompleted
                        }

                        val multipleApiExportDialog = actionContext!!.instance { SuvApiExportDialog() }

                        UIUtils.show(multipleApiExportDialog)
                        actionContext.runInSwingUI {
                            multipleApiExportDialog.setChannels(EXPORTER_CHANNELS)

                            multipleApiExportDialog.updateRequestList(requests
                                    .stream()
                                    .map { RequestWrapper(it.resource, it.name) }
                                    .toList())
                        }
                        multipleApiExportDialog.setApisHandle { channel, requests ->
                            doExport(channel as ApiExporterWrapper, requests as List<RequestWrapper>)
                        }
                    } catch (e: Exception) {
                        logger.error("Apis find failed" + ExceptionUtils.getStackTrace(e))
                    }
                }
                .traversal()
    }

    private var customActionExtLoader: ((String, ActionContext.ActionContextBuilder) -> Unit)? = null

    fun setCustomActionExtLoader(customActionExtLoader: (String, ActionContext.ActionContextBuilder) -> Unit) {
        this.customActionExtLoader = customActionExtLoader
    }

    protected fun loadCustomActionExt(actionName: String, builder: ActionContext.ActionContextBuilder) {
        customActionExtLoader?.let { it(actionName, builder) }
    }

    class RequestWrapper(var resource: Any?, var name: String?) {

        override fun toString(): String {
            return name ?: ""
        }
    }

    abstract class ApiExporterAdapter {

        @Inject(optional = true)
        protected var logger: Logger? = null

        @Inject
        protected val classExporter: ClassExporter? = null

        @Inject
        protected val psiClassHelper: PsiClassHelper? = null

        @Inject
        protected val actionContext: ActionContext? = null

        @Inject
        protected val requestHelper: RequestHelper? = null

        private var suvApiExporter: SuvApiExporter? = null

        fun setSuvApiExporter(suvApiExporter: SuvApiExporter) {
            this.suvApiExporter = suvApiExporter
        }

        fun exportApisFromMethod(actionContext: ActionContext, requests: List<RequestWrapper>) {

            this.logger = actionContext.instance(Logger::class)

            val actionContextBuilder = ActionContext.builder()
            actionContextBuilder.bindInstance(Project::class, actionContext.instance(Project::class))
            actionContextBuilder.bindInstance(AnActionEvent::class, actionContext.instance(AnActionEvent::class))
            actionContextBuilder.bindInstance(DataContext::class, actionContext.instance(DataContext::class))

            val resources = requests
                    .stream()
                    .filter { it != null }
                    .map { it.resource }
                    .filter { it != null }
                    .filter { it is PsiMethod }
                    .map { it as PsiMethod }
                    .toList()

            actionContextBuilder.bindInstance(MethodFilter::class, ExplicitMethodFilter(resources))

            onBuildActionContext(actionContext, actionContextBuilder)
            val newActionContext = actionContextBuilder.build()
            newActionContext.hold()
            Thread {
                try {
                    newActionContext.runAsync {
                        try {
                            newActionContext.init(this)
                            beforeExport {
                                newActionContext.runInReadUI {
                                    try {
                                        doExportApisFromMethod(requests)
                                    } catch (e: Exception) {
                                        logger!!.error("error to export apis:" + e.message)
                                        logger!!.traceError(e)
                                    }
                                }
                            }
                        } catch (e: Throwable) {
                            logger!!.error("error to export apis:" + e.message)
                            logger!!.traceError(e)
                        }
                    }
                } catch (e: Throwable) {
                    logger!!.error("error to export apis:" + e.message)
                    logger!!.traceError(e)
                } finally {
                    newActionContext.unHold()
                }
            }.start()

            actionContext.hold()

            newActionContext.on(EventKey.ONCOMPLETED) {
                actionContext.unHold()
            }

            newActionContext.waitCompleteAsync()
        }

        protected open fun beforeExport(next: () -> Unit) {
            next()
        }

        protected open fun onBuildActionContext(actionContext: ActionContext, builder: ActionContext.ActionContextBuilder) {

            builder.bindInstance("plugin.name", "easy_api")

            builder.inheritFrom(actionContext, SettingBinder::class)

            builder.inheritFrom(actionContext, Logger::class)

//            builder.bindInstance(Logger::class, BeanWrapperProxies.wrap(Logger::class, actionContext.instance(Logger::class)))

//            builder.bind(Logger::class) { it.with(ConfigurableLogger::class).singleton() }
//            builder.bind(Logger::class, "delegate.logger") { it.with(ConsoleRunnerLogger::class).singleton() }

            builder.bind(RuleParser::class) { it.with(SuvRuleParser::class).singleton() }
            builder.bind(PsiClassHelper::class) { it.with(CustomizedPsiClassHelper::class).singleton() }

            builder.bind(ClassExporter::class) { it.with(SpringClassExporter::class).singleton() }

            builder.bind(FileApiCacheRepository::class) { it.with(DefaultFileApiCacheRepository::class).singleton() }
            builder.bind(LocalFileRepository::class, "projectCacheRepository") {
                it.with(ProjectCacheRepository::class).singleton()
            }

            afterBuildActionContext(actionContext, builder)

            suvApiExporter?.loadCustomActionExt(actionName(), builder)
        }

        protected open fun actionName(): String {
            return "Basic"
        }

        protected open fun afterBuildActionContext(actionContext: ActionContext, builder: ActionContext.ActionContextBuilder) {

        }

        private fun doExportApisFromMethod(requestWrappers: List<RequestWrapper>) {

            val classes = requestWrappers
                    .stream()
                    .filter { it != null }
                    .map { it.resource }
                    .filter { it != null }
                    .map { psiClassHelper!!.getContainingClass(it as PsiMember) }
                    .distinct()
                    .toList()

            val requests: MutableList<Request> = ArrayList()
            for (cls in classes) {
                classExporter!!.export(cls!!, requestHelper!!) { request ->
                    requests.add(request)
                }
            }


            actionContext!!.runAsync {

                if (classExporter is Worker) {
                    classExporter.waitCompleted()
                }

                if (requests.isNullOrEmpty()) {
                    logger!!.info("no api has be found")
                }

                doExportRequests(requests)
            }
        }

        abstract fun doExportRequests(requests: MutableList<Request>)
    }

    class ApiExporterWrapper(val adapter: KClass<*>, val name: String) {
        override fun toString(): String {
            return name
        }
    }

    class ExplicitMethodFilter(private var methods: List<PsiMethod>) : MethodFilter {

        override fun checkMethod(method: PsiMethod): Boolean {
            return this.methods.contains(method)
        }
    }

    class PostmanApiExporterAdapter : ApiExporterAdapter() {

        @Inject
        private val postmanApiHelper: PostmanApiHelper? = null

        @Inject
        private val fileSaveHelper: FileSaveHelper? = null

        @Inject
        private val postmanFormatter: PostmanFormatter? = null

        override fun actionName(): String {
            return "PostmanExportAction"
        }

        override fun afterBuildActionContext(actionContext: ActionContext, builder: ActionContext.ActionContextBuilder) {
            super.afterBuildActionContext(actionContext, builder)

            builder.bind(LocalFileRepository::class) { it.with(DefaultLocalFileRepository::class).singleton() }

            builder.bind(PostmanApiHelper::class) { it.with(PostmanCachedApiHelper::class).singleton() }
            builder.bind(HttpClientProvider::class) { it.with(ConfigurableHttpClientProvider::class).singleton() }
            builder.bind(RequestHelper::class) { it.with(DefaultRequestHelper::class).singleton() }
            builder.bind(ConfigReader::class, "delegate_config_reader") { it.with(PostmanConfigReader::class).singleton() }
            builder.bind(ConfigReader::class) { it.with(RecommendConfigReader::class).singleton() }

            //always not read api from cache
            builder.bindInstance("class.exporter.read.cache", false)

            builder.bindInstance("file.save.default", "postman.json")
            builder.bindInstance("file.save.last.location.key", "com.itangcent.postman.export.path")

        }

        override fun doExportRequests(requests: MutableList<Request>) {

            try {
                val postman = postmanFormatter!!.parseRequests(requests)
                requests.clear()
                if (postmanApiHelper!!.hasPrivateToken()) {
                    logger!!.info("PrivateToken of postman be found")
                    val createdCollection = postmanApiHelper.createCollection(postman)

                    if (!createdCollection.isNullOrEmpty()) {
                        val collectionName = createdCollection["name"]?.toString()
                        if (StringUtils.isNotBlank(collectionName)) {
                            logger!!.info("Imported as collection:$collectionName")
                            return
                        }
                    }

                    logger!!.error("Export to postman failed,You could check below:" +
                            "1.the network " +
                            "2.the privateToken")

                } else {
                    logger!!.info("PrivateToken of postman not be setting")
                    logger!!.info("To enable automatically import to postman you could set privateToken of postman" +
                            "in \"Preference -> Other Setting -> EasyApi\"")
                    logger!!.info("If you do not have a privateToken of postman, you can easily generate one by heading over to the" +
                            " Postman Integrations Dashboard [https://go.postman.co/integrations/services/pm_pro_api].")
                }
                fileSaveHelper!!.saveOrCopy(GsonUtils.prettyJson(postman), {
                    logger!!.info("Exported data are copied to clipboard,you can paste to postman now")
                }, {
                    logger!!.info("Apis save success")
                }) {
                    logger!!.info("Apis save failed")
                }
            } catch (e: Exception) {
                logger!!.error("Apis save failed")
                logger!!.traceError(e)
            }

        }
    }

    class YapiApiExporterAdapter : ApiExporterAdapter() {

        @Inject
        private val yapiApiHelper: YapiApiHelper? = null

        @Inject
        private val project: Project? = null

        override fun onBuildActionContext(actionContext: ActionContext, builder: ActionContext.ActionContextBuilder) {
            super.onBuildActionContext(actionContext, builder)

            builder.inheritFrom(actionContext, Project::class)

            builder.bind(LocalFileRepository::class) { it.with(DefaultLocalFileRepository::class).singleton() }

            builder.bind(YapiApiHelper::class) { it.with(YapiCachedApiHelper::class).singleton() }

            builder.bind(HttpClientProvider::class) { it.with(ConfigurableHttpClientProvider::class).singleton() }
            builder.bind(RequestHelper::class) { it.with(YapiDefaultRequestHelper::class).singleton() }
            builder.bind(ConfigReader::class, "delegate_config_reader") { it.with(YapiConfigReader::class).singleton() }
            builder.bind(ConfigReader::class) { it.with(RecommendConfigReader::class).singleton() }

            builder.bind(ClassExporter::class) { it.with(YapiSpringClassExporter::class).singleton() }

            //always not read api from cache
            builder.bindInstance("class.exporter.read.cache", false)

            builder.bindInstance("file.save.default", "api.json")
            builder.bindInstance("file.save.last.location.key", "com.itangcent.api.export.path")


        }

        override fun beforeExport(next: () -> Unit) {
            val serverFound = !yapiApiHelper!!.findServer().isNullOrBlank()
            if (serverFound) {
                next()
            } else {
                actionContext!!.runAsync {
                    Thread.sleep(200)
                    actionContext.runInSwingUI {
                        val yapiServer = Messages.showInputDialog(project, "Input server of yapi",
                                "server of yapi", Messages.getInformationIcon())
                        if (yapiServer.isNullOrBlank()) {
                            logger!!.info("No yapi server")
                            return@runInSwingUI
                        }

                        yapiApiHelper.setYapiServer(yapiServer)

                        next()
                    }
                }
            }
        }

        override fun doExportRequests(requests: MutableList<Request>) {

            val suvYapiApiExporter = actionContext!!.init(SuvYapiApiExporter())

            try {
                requests.forEach { suvYapiApiExporter.exportRequest(it) }
            } catch (e: Exception) {
                logger!!.error("Apis export failed")
                logger!!.traceError(e)
            }
        }

        class SuvYapiApiExporter : AbstractYapiApiExporter() {

            //cls -> CartInfo
            private val clsCartMap: HashMap<PsiClass, CartInfo> = HashMap()

            override fun getCartForCls(psiClass: PsiClass): CartInfo? {

                var cartId = clsCartMap[psiClass]
                if (cartId != null) return cartId
                synchronized(clsCartMap)
                {
                    cartId = clsCartMap[psiClass]
                    if (cartId != null) return cartId

                    return super.getCartForCls(psiClass)
                }
            }

            private var tryInputTokenOfModule: HashSet<String> = HashSet()

            override fun getTokenOfModule(module: String): String? {
                val privateToken = super.getTokenOfModule(module)
                if (!privateToken.isNullOrBlank()) {
                    return privateToken
                }

                if (tryInputTokenOfModule.contains(module)) {
                    return null
                } else {
                    tryInputTokenOfModule.add(module)
                    val modulePrivateToken = actionContext!!.callInSwingUI {
                        return@callInSwingUI Messages.showInputDialog(project, "Input Private Token Of Module:$module",
                                "Yapi Private Token", Messages.getInformationIcon())
                    }
                    return if (modulePrivateToken.isNullOrBlank()) {
                        null
                    } else {
                        yapiApiHelper!!.setToken(module, modulePrivateToken)
                        modulePrivateToken
                    }
                }
            }

            private var successExportedCarts: MutableSet<String> = ContainerUtil.newConcurrentSet<String>()

            override fun exportRequest(request: Request, privateToken: String, cartId: String): Boolean {
                if (super.exportRequest(request, privateToken, cartId)) {
                    if (successExportedCarts.add(cartId)) {
                        logger!!.info("Export to ${yapiApiHelper!!.getCartWeb(yapiApiHelper.getProjectIdByToken(privateToken)!!, cartId)} success")
                    }
                    return true
                }
                return false
            }
        }
    }

    class MarkdownApiExporterAdapter : ApiExporterAdapter() {

        @Inject
        private val fileSaveHelper: FileSaveHelper? = null

        @Inject
        private val markdownFormatter: MarkdownFormatter? = null

        override fun actionName(): String {
            return "MarkdownExportAction"
        }

        override fun afterBuildActionContext(actionContext: ActionContext, builder: ActionContext.ActionContextBuilder) {
            super.afterBuildActionContext(actionContext, builder)

            builder.bind(LocalFileRepository::class) { it.with(DefaultLocalFileRepository::class).singleton() }

            builder.bind(RequestHelper::class) { it.with(DefaultRequestHelper::class).singleton() }

            builder.bind(ConfigReader::class, "delegate_config_reader") { it.with(EasyApiConfigReader::class).singleton() }
            builder.bind(ConfigReader::class) { it.with(RecommendConfigReader::class).singleton() }

            //always not read api from cache
            builder.bindInstance("class.exporter.read.cache", false)

            builder.bindInstance("file.save.default", "easy-api.md")
            builder.bindInstance("file.save.last.location.key", "com.itangcent.markdown.export.path")
        }

        override fun doExportRequests(requests: MutableList<Request>) {
            try {
                if (requests.isEmpty()) {
                    logger!!.info("No api be found to export!")
                    return
                }
                logger!!.info("Start parse apis")
                val apiInfo = markdownFormatter!!.parseRequests(requests)
                requests.clear()
                actionContext!!.runAsync {
                    try {
                        fileSaveHelper!!.saveOrCopy(apiInfo, {
                            logger!!.info("Exported data are copied to clipboard,you can paste to a md file now")
                        }, {
                            logger!!.info("Apis save success")
                        }) {
                            logger!!.info("Apis save failed")
                        }
                    } catch (e: Exception) {
                        logger!!.error("Apis save failed")
                        logger!!.traceError(e)
                    }
                }
            } catch (e: Exception) {
                logger!!.error("Apis save failed")
                logger!!.traceError(e)
            }
        }
    }

    private fun doExport(channel: ApiExporterWrapper, requests: List<RequestWrapper>) {
        if (requests.isNullOrEmpty()) {
            logger!!.info("no api has be selected")
            return
        }
        val adapter = channel.adapter.createInstance() as ApiExporterAdapter
        adapter.setSuvApiExporter(this)
        adapter.exportApisFromMethod(actionContext!!, requests)
    }

    companion object {

        private val EXPORTER_CHANNELS: List<*> = listOf(
                ApiExporterWrapper(YapiApiExporterAdapter::class, "Yapi"),
                ApiExporterWrapper(PostmanApiExporterAdapter::class, "Postman"),
                ApiExporterWrapper(MarkdownApiExporterAdapter::class, "Markdown")
        )

    }
}