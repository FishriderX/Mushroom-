package com.example.pikminhelper.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MushroomReachabilityTest {
    @Test
    fun rejectsDistanceWarnings() {
        assertTrue(MushroomReachability.isOutOfRangeText("距離太遠，無法參加"))
        assertTrue(MushroomReachability.isOutOfRangeText("超出參加範圍"))
        assertTrue(MushroomReachability.isOutOfRangeText("無法從目前位置參加這個蘑菇"))
    }

    @Test
    fun keepsNormalJoinableDetail() {
        assertFalse(MushroomReachability.isOutOfRangeText("華麗蘑菇 參加 預計 5 分鐘後結束"))
    }
}
