package com.music.bitchord.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecommendationAlgorithmTest {

    @Test
    fun `all four recommendation modes are persisted by name`() {
        assertEquals(
            listOf("APPLE", "SPOTIFY", "YOUTUBE", "ALL"),
            RecommendationAlgorithm.entries.map { it.name },
        )
        assertEquals(
            RecommendationAlgorithm.APPLE,
            RecommendationAlgorithm.of("APPLE"),
        )
        assertEquals(
            RecommendationAlgorithm.SPOTIFY,
            RecommendationAlgorithm.of("SPOTIFY"),
        )
        assertEquals(
            RecommendationAlgorithm.YOUTUBE,
            RecommendationAlgorithm.of("YOUTUBE"),
        )
        assertEquals(
            RecommendationAlgorithm.ALL,
            RecommendationAlgorithm.of("ALL"),
        )
    }

    @Test
    fun `unknown persisted recommendation mode inherits the safe default`() {
        assertEquals(RecommendationAlgorithm.YOUTUBE, RecommendationAlgorithm.of(null))
        assertEquals(RecommendationAlgorithm.YOUTUBE, RecommendationAlgorithm.of("REMOVED"))
    }

    @Test
    fun `single modes ask only themselves`() {
        for (algorithm in RecommendationAlgorithm.entries.filter { it != RecommendationAlgorithm.ALL }) {
            assertTrue(algorithm.includes(algorithm))
            for (other in RecommendationAlgorithm.entries.filter { it != algorithm }) {
                assertFalse(algorithm.includes(other))
            }
        }
    }

    @Test
    fun `all mode asks every recommendation source`() {
        assertTrue(RecommendationAlgorithm.ALL.includes(RecommendationAlgorithm.APPLE))
        assertTrue(RecommendationAlgorithm.ALL.includes(RecommendationAlgorithm.SPOTIFY))
        assertTrue(RecommendationAlgorithm.ALL.includes(RecommendationAlgorithm.YOUTUBE))
        assertTrue(RecommendationAlgorithm.ALL.includes(RecommendationAlgorithm.ALL))
    }
}
