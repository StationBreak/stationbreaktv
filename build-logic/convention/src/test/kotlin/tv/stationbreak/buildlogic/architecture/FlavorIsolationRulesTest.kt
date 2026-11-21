// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.buildlogic.architecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlavorIsolationRulesTest {
    private val sdkUse = "package a\nimport com.amazon.device.iap.PurchasingService\nval p = PurchasingService"

    private fun violations(
        module: String,
        vararg files: Pair<String, String>,
    ) = FlavorIsolationRules.violations(module, files.toMap())

    @Test
    fun amazonSdkIsAllowedInAppsAmazonSourceSets() {
        for (set in listOf("amazon", "amazonRelease", "testAmazonRelease", "androidTestAmazon")) {
            assertEquals(emptyList(), violations(":app", "src/$set/kotlin/a/Iap.kt" to sdkUse), set)
        }
    }

    @Test
    fun amazonSdkInSharedOrGithubOrDebugCodeIsRefusedWithItsLine() {
        for (set in listOf("main", "github", "githubRelease", "debug", "release", "test", "androidTest", "nonRelease")) {
            val found = violations(":app", "src/$set/kotlin/a/Iap.kt" to sdkUse)
            assertEquals(1, found.size, set)
            assertTrue(found[0].startsWith("src/$set/kotlin/a/Iap.kt:2 "), found[0])
        }
    }

    @Test
    fun amazonSdkInAnotherModuleIsRefusedEvenInItsAmazonSourceSet() {
        assertEquals(1, violations(":core:domain", "src/amazon/kotlin/a/Iap.kt" to sdkUse).size)
        assertEquals(1, violations(":data:settings", "src/main/kotlin/a/Iap.kt" to sdkUse).size)
    }

    @Test
    fun playServicesAndBillingAreRefusedEverywhere() {
        for (line in listOf(
            "import com.google.android.gms.common.GoogleApiAvailability",
            "import com.android.billingclient.api.BillingClient",
            "import com.android.vending.billing.IInAppBillingService",
        )) {
            assertEquals(1, violations(":app", "src/amazon/kotlin/a/B.kt" to line).size, line)
            assertEquals(1, violations(":app", "src/main/kotlin/a/B.kt" to line).size, line)
        }
    }

    @Test
    fun commentsThatExplainTheBanDoNotTripIt() {
        val text = "/**\n * Not com.amazon.device.iap.\n */\n// com.google.android.gms is out\nclass A"
        assertEquals(emptyList(), violations(":app", "src/main/kotlin/a/A.kt" to text))
    }

    @Test
    fun aNameThatMerelyLooksLikeItPasses() {
        assertEquals(emptyList(), violations(":app", "src/main/kotlin/a/A.kt" to "val s = \"x.com.amazonia.y\""))
    }

    @Test
    fun theOneSkuIsAConstNamedProSkuInProSkuKt() {
        val sku = "package a\nconst val PRO_SKU = \"stationbreak.pro\""
        val user = "package a\nval id = PRO_SKU"
        assertEquals(emptyList(), violations(":app", "src/amazon/kotlin/a/ProSku.kt" to sku, "src/amazon/kotlin/a/Use.kt" to user))
    }

    @Test
    fun aSkuWithAnotherNameOrFileIsRefused() {
        val badName = "val PRO_SKU_V2 = \"x\""
        assertEquals(1, violations(":app", "src/amazon/kotlin/a/ProSku.kt" to badName).size)
        val wrongFile = "const val PRO_SKU = \"x\""
        assertEquals(1, violations(":app", "src/amazon/kotlin/a/Iap.kt" to wrongFile).size)
    }

    @Test
    fun aSecondSkuDeclarationIsRefused() {
        val one = "const val PRO_SKU = \"a\""
        val found = violations(":app", "src/amazon/kotlin/a/ProSku.kt" to one, "src/amazon/kotlin/b/ProSku.kt" to one)
        assertEquals(1, found.count { "second SKU" in it })
    }

    @Test
    fun theSkuValueWrittenElsewhereIsRefused() {
        val sku = "const val PRO_SKU = \"stationbreak.pro\""
        val copy = "package a\nval id = \"stationbreak.pro\""
        val found = violations(":app", "src/amazon/kotlin/a/ProSku.kt" to sku, "src/amazon/kotlin/a/Iap.kt" to copy)
        assertEquals(1, found.size)
        assertTrue(found[0].startsWith("src/amazon/kotlin/a/Iap.kt:2 "), found[0])
    }

    @Test
    fun testsMayWriteTheSkuValue() {
        val sku = "const val PRO_SKU = \"stationbreak.pro\""
        assertEquals(
            emptyList(),
            violations(
                ":app",
                "src/amazon/kotlin/a/ProSku.kt" to sku,
                "src/testAmazonRelease/kotlin/a/T.kt" to "val x = \"stationbreak.pro\"",
            ),
        )
    }

    @Test
    fun aTreeWithNoSkuYetPasses() {
        assertEquals(emptyList(), violations(":app", "src/main/kotlin/a/A.kt" to "val x = 1"))
    }
}
