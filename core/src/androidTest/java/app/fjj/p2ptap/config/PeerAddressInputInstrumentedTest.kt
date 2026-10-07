package app.fjj.p2ptap.config

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeerAddressInputInstrumentedTest {
    private val peerId = "12D3KooWSMGgiTzD7aGiLJhNGmPhtS5142U8P3oTWderqTV92NZj"
    private val certHash = "uEiDKR_5wfQ9sprRwawTIzwqAtqIuokkOmw2ngF7D6LgTyA"
    private val endpoints = listOf("/ip4/43.138.143.71", "/ip6/2402:4e00:c012:1100:561c:f1e2:fef4:1")
        .flatMap { host -> listOf(
            "$host/udp/4002/webrtc-direct/certhash/$certHash/p2p/$peerId",
            "$host/udp/4001/quic-v1/p2p/$peerId",
            "$host/tcp/4001/p2p/$peerId"
        ) }

    @Test
    fun copiedSixAddressEntryPassesNativeAddressNormalization() {
        assertEquals(endpoints, AppConfigManager.normalizePeerAddresses(listOf(endpoints.joinToString(" "))))
    }

    @Test
    fun legacySavedSingleEntryPassesTheVpnStartupValidator() {
        val json = JSONObject().put("bootstrap_peers", JSONArray().put(endpoints.joinToString(" ")))
            .put("static_peers", JSONArray())
        val config = P2PConfig.fromJson(json.toString(), strict = false)
        assertEquals(endpoints, config.bootstrapPeers)
        AppConfigManager.validate(InstrumentationRegistry.getInstrumentation().targetContext, config)
    }

    @Test
    fun nativeValidatorStillRejectsMalformedAddressAfterSplitting() {
        try {
            AppConfigManager.normalizePeerAddresses(listOf("${endpoints[0]} not-a-peer-address"))
            fail("Malformed address must not be ignored")
        } catch (error: IllegalArgumentException) {
            assertTrue(error is app.fjj.p2ptap.i18n.LocalizedException)
            error as app.fjj.p2ptap.i18n.LocalizedException
            assertEquals(app.fjj.p2ptap.R.string.error_peer_address_fmt, error.messageRes)
            assertEquals(listOf(2), error.formatArgs)
            assertNotNull(error.cause)
        }
    }
}
