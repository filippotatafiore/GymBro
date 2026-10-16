package com.example.gymbro.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// What the profile page of the logged user shows besides the posts and the plans: how many
// followers and followed users, and the profile picture (JPEG). One row of the "profiles" table of
// the local database, saved at every refresh of the profile, so that it can be seen also offline.
// (The username, the email, the body data and the look are in UserSession.)
@Entity(tableName = "profiles")
class ProfileEntity(
    @PrimaryKey val userId: Long,
    val followersCount: Int,
    val followingCount: Int,
    val picture: ByteArray?                 // null = no profile picture
)
