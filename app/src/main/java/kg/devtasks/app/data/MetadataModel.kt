package kg.devtasks.app.data

import javax.xml.parsers.DocumentBuilderFactory
import kg.devtasks.app.core.UserError
import org.w3c.dom.Element

class MetadataModel(xml: String) {
    val sets = linkedMapOf<String, String>()
    val enums = mutableMapOf<String, List<String>>()
    val properties = mutableMapOf<String, MutableMap<String, String>>()
    private val targets = mutableMapOf<String, MutableMap<String, String>>()
    private val aliases = mutableMapOf<String, String>()
    private val bases = mutableMapOf<String, String>()

    fun canonical(raw: String): String {
        val type = raw.trim().removePrefix("Collection(").removeSuffix(")").trim()
        if ('.' !in type) return type
        return (aliases[type.substringBeforeLast('.')] ?: type.substringBeforeLast('.')) +
            "." +
            type.substringAfterLast('.')
    }

    private fun hierarchy(type: String): List<String> {
        var current = canonical(type)
        val seen = linkedSetOf<String>()
        while (current.isNotEmpty() && seen.add(current)) current = bases[current].orEmpty()
        return seen.toList()
    }

    fun property(type: String, key: String): String? =
        hierarchy(type).firstNotNullOfOrNull { properties[it]?.get(key) }

    fun target(type: String, key: String): String? =
        hierarchy(type).firstNotNullOfOrNull { targets[it]?.get(key) }

    fun reference(type: String, key: String): String? {
        target(type, key)?.let {
            return it
        }
        for (t in hierarchy(type)) {
            val entity = t.removeSuffix("_RowType").removeSuffix("_RecordType")
            if (
                entity != t &&
                    property(t, "${key}_Key") == "Edm.Guid" &&
                    property(entity, "${key}_Key") == "Edm.Guid"
            )
                target(entity, key)?.let {
                    return it
                }
        }
        return null
    }

    fun setFor(type: String): String? =
        sets.entries.firstOrNull { it.value == canonical(type) }?.key

    init {
        if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true))
            throw UserError("Внешние сущности в метаданных запрещены.")
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
        listOf(
                "http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            )
            .forEach { runCatching { factory.setFeature(it, false) } }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        val schemas =
            doc.getElementsByTagNameNS("*", "Schema").let { list ->
                (0 until list.length).map { list.item(it) as Element }
            }

        fun Element.children(): List<Element> =
            (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
        schemas.forEach { s ->
            val ns = s.getAttribute("Namespace")
            aliases[ns] = ns
            s.getAttribute("Alias").takeIf { it.isNotEmpty() }?.let { aliases[it] = ns }
        }
        val ends = mutableMapOf<String, Map<String, String>>()
        schemas.forEach { s ->
            val ns = s.getAttribute("Namespace")
            s.children()
                .filter { it.localName == "Association" }
                .forEach { a ->
                    ends["$ns.${a.getAttribute("Name")}"] =
                        a.children()
                            .filter { it.localName == "End" }
                            .associate {
                                it.getAttribute("Role") to canonical(it.getAttribute("Type"))
                            }
                }
        }
        schemas.forEach { s ->
            val ns = s.getAttribute("Namespace")
            s.children()
                .filter { it.localName in listOf("EntityType", "ComplexType") }
                .forEach { e ->
                    val type = "$ns.${e.getAttribute("Name")}"
                    val props = mutableMapOf<String, String>()
                    val nav = mutableMapOf<String, String>()
                    e.getAttribute("BaseType")
                        .takeIf { it.isNotEmpty() }
                        ?.let { bases[type] = canonical(it) }
                    e.children().forEach { p ->
                        val name = p.getAttribute("Name")
                        when (p.localName) {
                            "Property" -> props[name] = canonical(p.getAttribute("Type"))
                            "NavigationProperty" -> {
                                val target =
                                    p.getAttribute("Type")
                                        .takeIf { it.isNotEmpty() }
                                        ?.let(::canonical)
                                        ?: ends[canonical(p.getAttribute("Relationship"))]?.get(
                                            p.getAttribute("ToRole")
                                        )
                                if (target != null) nav[name] = target
                            }
                        }
                    }
                    properties[type] = props
                    targets[type] = nav
                }
        }
        val all = doc.getElementsByTagNameNS("*", "EntitySet")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            val name = e.getAttribute("Name")
            if (Regex("^[\\p{L}_][\\p{L}\\p{N}_]*$").matches(name))
                sets[name] = canonical(e.getAttribute("EntityType"))
        }
        val enumerations = doc.getElementsByTagNameNS("*", "EnumType")
        for (i in 0 until enumerations.length) {
            val e = enumerations.item(i) as Element
            enums[e.getAttribute("Name")] =
                e.children().filter { it.localName == "Member" }.map { it.getAttribute("Name") }
        }
        if (sets.isEmpty()) throw UserError("Метаданные OData не содержат коллекций.")
    }
}
