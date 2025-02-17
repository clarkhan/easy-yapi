package com.itangcent.common.kit

import com.itangcent.common.constant.Attrs
import com.itangcent.common.utils.KV
import com.itangcent.common.utils.joinToString
import com.itangcent.common.utils.notNullOrEmpty
import com.itangcent.utils.isCollections
import com.itangcent.utils.subMutable

object KVUtils {

    /**
     * tay get all comment info
     * 1.common comment
     * 2.options
     * 3....
     */
    @Suppress("UNCHECKED_CAST")
    fun getUltimateComment(comment: Map<*, *>?, field: Any?): String {
        if (comment == null || field == null) return ""
        var desc = comment[field] as? String
        val options = comment["$field@options"]
        if (options != null) {
            val optionList = options as List<Map<String, Any?>>

            val optionDesc = getOptionDesc(optionList)
            if (optionDesc.notNullOrEmpty()) {
                desc = if (desc.isNullOrBlank()) {
                    optionDesc
                } else {
                    desc + "\n" + optionDesc
                }
            }

        }
        return desc ?: ""
    }

    /**
     * get description of options
     */
    fun getOptionDesc(options: List<Map<String, Any?>>): String? {
        return options.stream()
            .map { concat(it["value"]?.toString(), it["desc"]?.toString()) }
            .filter { it != null }
            .joinToString("\n")
    }

    /**
     * get description of constants
     */
    fun getConstantDesc(constants: List<Map<String, Any?>>): String? {
        return constants.stream()
            .map { concat(it["name"]?.toString(), it["desc"]?.toString()) }
            .filter { it != null }
            .joinToString("\n")
    }

    private fun concat(name: String?, desc: String?): String? {
        if (name.isNullOrEmpty()) {
            return null
        }
        if (desc.isNullOrEmpty()) {
            return name
        }
        return "$name :$desc"
    }

    @Suppress("UNCHECKED_CAST")
    fun addKeyComment(model: Any?, key: String, comment: String): Boolean {

        if (model is Collection<*>) {
            if (model.isEmpty()) {
                return false
            }
            return addKeyComment(model.first(), key, comment)

        }

        if (model is Array<*>) {
            if (model.isEmpty()) {
                return false
            }
            return addKeyComment(model.first(), key, comment)
        }

        if (model is Map<*, *>) {
            return if (key.contains(".")) {
                val headerKey = key.substringBefore('.')
                val restKey = key.substringAfter('.')
                addKeyComment(model[headerKey], restKey, comment)
            } else {
                addComment(model as (HashMap<Any, Any?>), key, comment)
                true
            }
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    fun addComment(info: HashMap<Any, Any?>, field: String, comment: String?) {
        var comments = info[Attrs.COMMENT_ATTR]
        if (comments == null) {
            comments = KV<String, Any?>()
            info[Attrs.COMMENT_ATTR] = comments
            comments[field] = comment
        } else {
            val oldComment = (comments as HashMap<Any?, Any?>)[field]
            if (oldComment == null) {
                comments[field] = comment
            } else {
                comments[field] = "$oldComment\n$comment"
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun addKeyOptions(model: Any?, key: String, options: ArrayList<HashMap<String, Any?>>): Boolean {

        if (model is Collection<*>) {
            if (model.isEmpty()) {
                return false
            }
            return addKeyOptions(model.first(), key, options)
        }

        if (model is Array<*>) {
            if (model.isEmpty()) {
                return false
            }
            return addKeyOptions(model.first(), key, options)
        }

        if (model is Map<*, *>) {
            return if (key.contains(".")) {
                val headerKey = key.substringBefore('.')
                val restKey = key.substringAfter('.')
                addKeyOptions(model[headerKey], restKey, options)
            } else {
                addOptions(model as HashMap<Any, Any?>, key, options)
                true
            }
        }

        return false

    }

    @Suppress("UNCHECKED_CAST")
    fun addOptions(info: HashMap<Any, Any?>, field: String, options: ArrayList<HashMap<String, Any?>>) {
        var comments = info[Attrs.COMMENT_ATTR]
        if (comments == null) {
            comments = KV<String, Any?>()
            info[Attrs.COMMENT_ATTR] = comments
            comments["$field@options"] = options
        } else {
            val oldOptions = (comments as HashMap<Any?, Any?>)["$field@options"]
            if (oldOptions == null) {
                comments["$field@options"] = options
            } else {
                val mergeOptions: ArrayList<Any?> = ArrayList(oldOptions as ArrayList<*>)
                mergeOptions.addAll(options)
                comments["$field@options"] = mergeOptions
            }
        }
    }

    fun useFieldAsAttrs(model: Any?, attr: String) {
        when (model) {
            null -> {
                return
            }

            is Collection<*> -> {
                if (model.isEmpty()) {
                    return
                }
                return useFieldAsAttrs(model.first(), attr)
            }

            is Array<*> -> {
                if (model.isEmpty()) {
                    return
                }
                return useFieldAsAttrs(model.first(), attr)
            }

            is Map<*, *> -> {
                val keys = model.keys.toList()
                keys.forEach { key->
                    if (key !is String) {
                        return@forEach
                    }
                    val value = model[key]
                    if (!value.isCollections()) {
                        model.subMutable(attr)?.set(key, value)
                    }
                    useFieldAsAttrs(value, attr)
                }
            }
        }
    }
}