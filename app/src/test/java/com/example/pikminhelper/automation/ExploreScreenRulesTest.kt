package com.example.pikminhelper.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreScreenRulesTest {
    @Test
    fun decorAndSeedlingsAloneAreNotMushroomList() {
        val screen = "探險 花苗和水果 飾品一覽"
        assertFalse(ExploreScreenRules.isMushroomList(screen))
        assertTrue(ExploreScreenRules.isDecorOnlyExplore(screen))
    }

    @Test
    fun mushroomDailyHeaderIsMushroomList() {
        assertTrue(ExploreScreenRules.isMushroomList("蘑菇 今天還剩下 3 次"))
    }

    @Test
    fun mushroomPositionIsMushroomList() {
        assertTrue(ExploreScreenRules.isMushroomList("蘑菇：1/16"))
    }

    @Test
    fun exploreBodyNeedsMushroomEvidence() {
        assertTrue(ExploreScreenRules.isMushroomList("花苗和水果 飾品一覽 華麗蘑菇"))
        assertFalse(ExploreScreenRules.isDecorOnlyExplore("花苗和水果 飾品一覽 華麗蘑菇"))
    }
}
