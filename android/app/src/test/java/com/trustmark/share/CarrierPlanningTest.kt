package com.trustmark.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarrierPlanningTest {
    @Test fun standardModeAllowsMoreThanTwentyFiveParts() {
        val size = 520L * 1024 * 1024
        val plan = CarrierPlanner.plan(size, listOf(100_000L))
        assertEquals(26, plan.partCount)
        assertEquals(size, plan.plainSizes.sum())
        assertTrue(plan.plainSizes.all { it <= CarrierPlanner.STANDARD_PLAIN_BYTES })
    }

    @Test fun automaticModeIncludesProtocolOverhead() {
        val target = 3_500_000L
        val plan = CarrierPlanner.plan(
            secretSize = 7_000_000L,
            coverSizes = listOf(250_000L),
            allocation = CarrierAllocation(CarrierAllocationMode.AUTO, target),
            metadataSize = 400,
        )
        assertEquals(3, plan.partCount)
        assertEquals(7_000_000L, plan.plainSizes.sum())
        assertTrue(plan.appendSizes.all { it <= target })
        assertEquals(plan.plainSizes[0] + 16 + 112 + 400, plan.appendSizes[0])
    }

    @Test fun automaticCapacityUsesDecimalMegabytesAndHalfMegabyteSteps() {
        val accepted = listOf(1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L, 3_000_000L, 3_500_000L, 4_000_000L)
        accepted.forEach { target ->
            CarrierPlanner.plan(1, listOf(100), CarrierAllocation(CarrierAllocationMode.AUTO, target))
        }
        val failure = runCatching {
            CarrierPlanner.plan(1, listOf(100), CarrierAllocation(CarrierAllocationMode.AUTO, 3_600_000L))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test fun manualCoversStillUseStandardTwentyMiBCapacity() {
        val plan = CarrierPlanner.plan(25L * 1024 * 1024, listOf(100L, 200L), manualCovers = true)
        assertEquals(2, plan.partCount)
        assertTrue(plan.plainSizes.all { it <= CarrierPlanner.STANDARD_PLAIN_BYTES })
    }
}
