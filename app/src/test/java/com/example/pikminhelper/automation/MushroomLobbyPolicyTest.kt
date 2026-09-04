package com.example.pikminhelper.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MushroomLobbyPolicyTest {
    @Test
    fun onlyKnownCountsBelowFiveAreJoinable() {
        assertTrue(MushroomLobbyPolicy.canJoin(0))
        assertTrue(MushroomLobbyPolicy.canJoin(4))
        assertFalse(MushroomLobbyPolicy.canJoin(5))
        assertFalse(MushroomLobbyPolicy.canJoin(6))
        assertFalse(MushroomLobbyPolicy.canJoin(null))
    }
}
