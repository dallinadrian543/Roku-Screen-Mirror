package com.dhatech.roku.airplay.discovery

import com.dhatech.roku.airplay.plist.asString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class EndpointDiscoveryTest {

    @Test
    fun `parses Roku ECP device-info XML`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <device-info>
                <udn>29600008-5400-10a6-8000-d83134001122</udn>
                <serial-number>YH0012345678</serial-number>
                <model-name>Roku Ultra</model-name>
                <model-number>4800X</model-number>
                <wifi-mac>d8:31:34:00:11:22</wifi-mac>
                <user-device-name>Living Room Roku</user-device-name>
                <friendly-device-name>Living Room TV</friendly-device-name>
                <software-version>13.1.4</software-version>
            </device-info>
        """.trimIndent()

        val info = EndpointProber.parseRokuDeviceInfoXml(xml)
        assertNotNull(info)
        assertEquals("Living Room TV", info!!.name)
        assertEquals("Roku Ultra", info.model)
        assertEquals("d8:31:34:00:11:22", info.deviceId)
        assertEquals("13.1.4", info.softwareVersion)
    }

    @Test
    fun `parses XML plist fallback from AirPlay info endpoint`() {
        val xmlPlist = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>name</key>
                <string>Bedroom Roku</string>
                <key>deviceID</key>
                <string>D8:31:34:AA:BB:CC</string>
                <key>model</key>
                <string>Roku Express 4K+</string>
                <key>features</key>
                <integer>128</integer>
                <key>srcvers</key>
                <string>377.40.0</string>
            </dict>
            </plist>
        """.trimIndent()

        val receiver = EndpointProber.parseInfoReceiver(
            address = InetAddress.getByName("192.168.1.50"),
            port = 7100,
            body = xmlPlist.toByteArray(Charsets.UTF_8),
            source = DiscoverySource.LAN_SCAN,
        )
        assertNotNull(receiver)
        assertEquals("Bedroom Roku", receiver!!.name)
        assertEquals(7100, receiver.port)
        assertEquals("D8:31:34:AA:BB:CC", receiver.deviceId)
        assertEquals("377.40.0", receiver.sourceVersion)
        assertTrue(receiver.supportsMirroring)
        assertTrue(receiver.isRoku)
    }

    @Test
    fun `auto-selects mirroring port over audio-only port and records audio capability`() {
        val addr = InetAddress.getByName("192.168.1.60")
        val raopOnly = AirPlayReceiver(
            name = "Roku 5000",
            address = addr,
            port = 5000,
            deviceId = "AA:BB:CC:DD:EE:FF",
            model = "Roku TV",
            sourceVersion = null,
            publicIdentifier = null,
            publicKeyHex = null,
            features = FeatureFlags(1L shl 9),
            isAudioOnly = true,
            discoverySource = DiscoverySource.LAN_SCAN,
        )
        val altMirror = AirPlayReceiver(
            name = "Roku TV",
            address = addr,
            port = 7100,
            deviceId = "AA:BB:CC:DD:EE:FF",
            model = "Roku TV",
            sourceVersion = "550.10",
            publicIdentifier = null,
            publicKeyHex = "deadbeef",
            features = FeatureFlags(1L shl 7),
            discoverySource = DiscoverySource.LAN_SCAN,
        )

        val selected = EndpointProber.rankAndMergePortResults(listOf(raopOnly, altMirror))
        assertNotNull(selected)
        assertEquals(7100, selected!!.port)
        assertTrue(selected.supportsMirroring)
        assertTrue(selected.hasAudioOnlyService)
    }

    @Test
    fun `extracts Roku IP and ECP port from SSDP reply`() {
        val payload = "HTTP/1.1 200 OK\r\n" +
            "Cache-Control: max-age=3600\r\n" +
            "ST: roku:ecp\r\n" +
            "LOCATION: http://192.168.1.77:8060/\r\n" +
            "USN: uuid:roku:ecp:YH00123\r\n\r\n"

        val target = RokuSsdpScanner.extractRokuTarget(
            payload,
            InetAddress.getByName("192.168.1.77"),
        )
        assertNotNull(target)
        assertEquals("192.168.1.77", target!!.address.hostAddress)
        assertEquals(8060, target.ecpPort)
    }

    @Test
    fun `computes local subnet hosts excluding self and capping large prefixes`() {
        val local = InetAddress.getByName("192.168.50.12") as Inet4Address
        val hosts24 = SubnetScanner.computeSubnetHosts(local, 24)
        assertEquals(253, hosts24.size)
        assertFalse(hosts24.any { it.hostAddress == "192.168.50.12" })
        assertFalse(hosts24.any { it.hostAddress == "192.168.50.0" })
        assertFalse(hosts24.any { it.hostAddress == "192.168.50.255" })
        assertTrue(hosts24.any { it.hostAddress == "192.168.50.1" })

        val hosts16 = SubnetScanner.computeSubnetHosts(local, 16)
        assertEquals(253, hosts16.size)
    }

    @Test
    fun `splits inline host and port safely`() {
        assertEquals("192.168.1.45" to 7100, ReceiverDiscovery.splitHostAndPort(" 192.168.1.45:7100 "))
        assertEquals("192.168.1.45" to null, ReceiverDiscovery.splitHostAndPort("192.168.1.45"))
        assertEquals("fe80::1" to null, ReceiverDiscovery.splitHostAndPort("fe80::1"))
    }

    @Test
    fun `merges receivers across discovery sources and folds audio-only duplicates`() {
        val addr = InetAddress.getByName("192.168.1.88")
        val scanMirror = AirPlayReceiver(
            name = "Family Room Roku",
            address = addr,
            port = 7000,
            deviceId = "11:22:33:44:55:66",
            model = "Roku TV",
            sourceVersion = null,
            publicIdentifier = null,
            publicKeyHex = null,
            features = FeatureFlags(1L shl 7),
            discoverySource = DiscoverySource.SSDP_ECP,
        )
        val nsdMirror = scanMirror.copy(
            port = 7000,
            publicKeyHex = "cafe",
            discoverySource = DiscoverySource.NSD,
        )
        val mdnsAudioOnly = scanMirror.copy(
            port = 5000,
            isAudioOnly = true,
            features = FeatureFlags(1L shl 9),
            discoverySource = DiscoverySource.MDNS,
        )

        val merged = ReceiverDiscovery.mergeReceivers(listOf(scanMirror, nsdMirror, mdnsAudioOnly))
        assertEquals(1, merged.size)
        val winner = merged.first()
        assertEquals(7000, winner.port)
        assertEquals("cafe", winner.publicKeyHex)
        assertEquals(DiscoverySource.NSD, winner.discoverySource)
        assertTrue(winner.hasAudioOnlyService)
        assertNull(EndpointProber.parseRokuDeviceInfoXml("<not-roku/>"))
        assertEquals(
            "Family Room Roku",
            EndpointProber.parseXmlPlistDict(
                "<plist><dict><key>name</key><string>Family Room Roku</string></dict></plist>",
            )
                ?.get("name")?.asString(),
        )
    }

    @Test
    fun `requiresPassword handles absent sf, hex sf, and explicit pw TXT records`() {
        val base = AirPlayReceiver(
            name = "OEM TV",
            address = InetAddress.getByName("192.168.1.99"),
            port = 7000,
            deviceId = "AA:BB:CC:11:22:33",
            model = "7104X",
            sourceVersion = null,
            publicIdentifier = null,
            publicKeyHex = null,
            features = FeatureFlags(1L shl 7),
        )
        assertFalse(base.requiresPassword)
        assertFalse(base.copy(txtRecords = mapOf("sf" to "0x200")).requiresPassword)
        assertTrue(base.copy(txtRecords = mapOf("sf" to "0x204")).requiresPassword)
        assertFalse(base.copy(txtRecords = mapOf("pw" to "false", "sf" to "0x204")).requiresPassword)
        assertTrue(base.copy(txtRecords = mapOf("pw" to "true")).requiresPassword)

        val infoXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0">
            <dict>
                <key>name</key><string>Guest Room TV</string>
                <key>model</key><string>C105X</string>
                <key>features</key><integer>128</integer>
                <key>statusFlags</key><integer>516</integer>
                <key>pw</key><false/>
            </dict>
            </plist>
        """.trimIndent()
        val parsed = EndpointProber.parseInfoReceiver(
            address = InetAddress.getByName("192.168.1.99"),
            port = 7000,
            body = infoXml.toByteArray(Charsets.UTF_8),
            source = DiscoverySource.LAN_SCAN,
        )
        assertNotNull(parsed)
        assertFalse(parsed!!.requiresPassword)
        assertEquals("0x204", parsed.txtRecords["sf"])
        assertEquals("false", parsed.txtRecords["pw"])
    }
}
