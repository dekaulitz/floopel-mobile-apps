package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

interface TravelerCommentRepository {
    suspend fun page(target: CommentTarget, id: String, cursor: String? = null, parentId: String? = null): CatalogResult<CommentPage>
    suspend fun total(target: CommentTarget, id: String): CatalogResult<Long>
    suspend fun create(target: CommentTarget, id: String, body: String, parentId: String?): CatalogResult<TravelerComment>
    suspend fun like(id: String, liked: Boolean, key: String): CatalogResult<CommentLike>
}
