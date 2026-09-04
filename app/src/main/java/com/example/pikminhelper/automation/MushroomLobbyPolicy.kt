package com.example.pikminhelper.automation

object MushroomLobbyPolicy {
    /**
     * Safety-first lobby rule: only proceed when participant count is known
     * and below the free-slot limit. Unknown is rejected deliberately.
     */
    fun canJoin(participantCount: Int?, freeSlotLimit: Int = 5): Boolean =
        participantCount != null && participantCount in 0 until freeSlotLimit
}
