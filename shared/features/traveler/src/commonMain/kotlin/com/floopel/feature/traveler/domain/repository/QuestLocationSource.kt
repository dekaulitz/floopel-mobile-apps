package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

interface QuestLocationSource { suspend fun current(): QuestLocation }
