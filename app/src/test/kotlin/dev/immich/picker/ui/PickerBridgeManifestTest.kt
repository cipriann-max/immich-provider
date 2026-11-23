package dev.immich.picker.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the bridge activity's manifest entry. Android never delivers `onActivityResult()`
 * to a `noHistory` activity, so the flag would silently drop every picked file.
 */
class PickerBridgeManifestTest {

    @Test
    fun bridgeActivityIsNotNoHistory() {
        val manifest = File("src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = doc.getElementsByTagName("activity")
        var found = 0
        for (i in 0 until activities.length) {
            val element = activities.item(i) as org.w3c.dom.Element
            val name = element.getAttributeNS(ANDROID_NS, "name")
            if (name.endsWith("SafPickerBridgeActivity")) {
                found++
                assertNotEquals("true", element.getAttributeNS(ANDROID_NS, "noHistory"))
            }
        }
        assertEquals("bridge activity must be declared once", 1, found)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
