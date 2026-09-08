package com.zayants.bmsmultiprobe

import com.zayants.bmsmultiprobe.ui.UiText
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

class PresentationResourcesTest {
    private val source = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }

    private fun entries(path: String, tag: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(source, path))
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private fun arrayItems(name: String): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(source, "res/values/appearance.xml"))
        val arrays = document.getElementsByTagName("string-array")
        val array = (0 until arrays.length).map { arrays.item(it) }
            .first { it.attributes.getNamedItem("name").nodeValue == name }
        val children = array.childNodes
        return (0 until children.length).map { children.item(it) }
            .filter { it.nodeName == "item" }.map { it.textContent.trim() }
    }

    @Test fun allThreeLanguagesHaveCompleteAndCompatibleStrings() {
        val english = entries("res/values/strings.xml", "string")
        val placeholders = Regex("%(\\d+)\\\$[.\\d]*[sdf]")
        for (language in arrayItems("language_tags")) {
            val directory = if (language == "en") "values" else "values-$language"
            val translated = entries("res/$directory/strings.xml", "string")
            assertEquals("Missing/extra keys in $language", english.keys, translated.keys)
            translated.forEach { (key, value) ->
                assertTrue("$language/$key is empty", value.isNotBlank())
                val expected = placeholders.findAll(english.getValue(key)).map { it.value }.sorted().toList()
                val actual = placeholders.findAll(value).map { it.value }.sorted().toList()
                assertEquals("$language/$key format", expected, actual)
                val args = actual.associate { match ->
                    val index = match.substringAfter('%').substringBefore('$').toInt() - 1
                    index to when (match.last()) { 'd' -> 3; 'f' -> 3.27; else -> "test" }
                }
                if (args.isNotEmpty()) {
                    val values = Array<Any>(args.keys.max() + 1) { args[it] ?: "" }
                    assertTrue(String.format(Locale.forLanguageTag(language), value, *values).isNotBlank())
                }
            }
        }
    }

    @Test fun languagePickerAndBrowserCatalogStayInSync() {
        val tags = arrayItems("language_tags")
        assertTrue(tags.containsAll(listOf("en", "ru", "uk")))
        assertEquals(tags.size, tags.distinct().size)
        assertEquals(tags.size, arrayItems("language_names").size)
        val browser = File(source, "kotlin/com/zayants/bmsmultiprobe/web/BrowserPage.kt").readText()
        entries("res/values/strings.xml", "string").keys.forEach { key ->
            assertTrue("Browser missing $key", browser.contains("\"$key\" to R.string.$key"))
        }
    }

    @Test fun themesHaveIdenticalSemanticColors() {
        val light = entries("res/values/colors.xml", "color")
        val dark = entries("res/values-night/colors.xml", "color")
        assertEquals(light.keys, dark.keys)
        for (palette in listOf(light, dark)) {
            for (text in listOf("ui_text", "ui_muted", "ui_alarm", "ui_success")) {
                for (background in listOf("ui_background", "ui_surface")) {
                    assertTrue("$text on $background must be readable", contrast(palette.getValue(text), palette.getValue(background)) >= 4.5)
                }
            }
        }
    }

    @Test fun everyCurrentTransportStatusHasAPresentationMapping() {
        val transport = File(source, "kotlin/com/zayants/bmsmultiprobe/ble/MultiBmsBleManager.kt").readText()
        val statuses = Regex("status = \"([^\"]+)\"").findAll(transport).map { it.groupValues[1]
            .replace("\$mtu", "247").replace("\$result", "133") }.toSet()
        assertTrue(statuses.size > 15)
        statuses.forEach { status ->
            assertNotEquals(status, R.string.status_unknown, UiText.statusText(status).resource)
        }
        assertEquals("133", UiText.statusText("notification error 133").argument)
        assertEquals("247", UiText.statusText("MTU 247; discovering").argument)
        assertEquals(R.string.status_unknown, UiText.statusText("unexpected").resource)
    }

    @Test fun everyDecodedAlarmHasATranslation() {
        val protocol = File(source, "kotlin/com/zayants/bmsmultiprobe/ble/JkReadOnlyProtocol.kt").readText()
            .substringAfter("private val JK02_24S_ALARMS")
        val alarms = Regex("\"([^\"]+)\"").findAll(protocol).map { it.groupValues[1] }.toSet()
        assertEquals(15, alarms.size)
        assertEquals(alarms, UiText.alarms.keys)
    }

    private fun contrast(first: String, second: String): Double {
        fun luminance(hex: String): Double {
            val parts = listOf(1, 3, 5).map { offset ->
                val channel = hex.substring(offset, offset + 2).toInt(16) / 255.0
                if (channel <= 0.04045) channel / 12.92 else Math.pow((channel + 0.055) / 1.055, 2.4)
            }
            return parts[0] * 0.2126 + parts[1] * 0.7152 + parts[2] * 0.0722
        }
        val a = luminance(first); val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
