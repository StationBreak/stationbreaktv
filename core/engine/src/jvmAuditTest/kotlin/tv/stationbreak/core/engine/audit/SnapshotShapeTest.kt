// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-StationBreak-AppStore
package tv.stationbreak.core.engine.audit

import tv.stationbreak.core.model.RuleCollection
import tv.stationbreak.core.model.RuleItem
import tv.stationbreak.core.model.RuleSeries
import tv.stationbreak.core.model.RuleSnapshot
import tv.stationbreak.core.model.schedule.ScheduleItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The audit-snapshot format has a field for every field of these types. If one gains or loses a field this fails; the
 * format then needs a decision.
 */
class SnapshotShapeTest {
    private fun fields(type: Class<*>): Int = type.methods.count { Regex("component\\d+(-.*)?").matches(it.name) }

    @Test
    fun theTypesTheSnapshotMirrorsHaveTheFieldsTheFormatHas() {
        val expected =
            mapOf(
                RuleSnapshot::class.java to 6,
                RuleItem::class.java to 8,
                ScheduleItem::class.java to 10,
                RuleSeries::class.java to 7,
                RuleCollection::class.java to 6,
            )
        for ((type, count) in expected) {
            assertEquals(
                count,
                fields(type),
                "${type.simpleName} changed: bump audit-snapshot version and update FORMAT.md, export.py, " +
                    "AuditExportFixtureTest, SnapshotWriter",
            )
        }
    }
}
