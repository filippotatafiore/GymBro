package com.example.gymbro.social

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.network.SocialUser
import kotlinx.coroutines.CoroutineScope

// Fills the lists of users of the social page (search, followers, following): one row
// (res/layout/item_user.xml) per user, with picture, username and how they are related to the
// logged user (e.g. "Follows you"). Tap a row to open the profile. scope: of the page (pictures);
// myId: the logged user ("You").
class UserAdapter(
    private val scope: CoroutineScope,
    private val myId: Long?,
    private val onClick: (SocialUser) -> Unit
) : ListAdapter<SocialUser, UserAdapter.UserViewHolder>(DIFF) {

    class UserViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatar: ImageView = view.findViewById(R.id.userAvatar)
        val name: TextView = view.findViewById(R.id.userName)
        val relation: TextView = view.findViewById(R.id.userRelation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
        return UserViewHolder(view)
    }

    override fun onBindViewHolder(holder: UserViewHolder, position: Int) {
        val user = getItem(position)
        Avatars.show(holder.avatar, user.id, user.hasPicture, scope)
        holder.name.text = user.username
        val relation = relationText(holder.itemView.context, user, myId)
        holder.relation.text = relation
        holder.relation.isVisible = relation != null
        holder.itemView.setOnClickListener { onClick(user) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SocialUser>() {
            override fun areItemsTheSame(old: SocialUser, new: SocialUser) = old.id == new.id
            override fun areContentsTheSame(old: SocialUser, new: SocialUser) = old == new
        }

        // e.g. "Follows you", "You follow each other"; null: no relation
        fun relationText(context: Context, user: SocialUser, myId: Long?): String? = when {
            user.id == myId -> context.getString(R.string.you)
            user.isMutual -> context.getString(R.string.you_follow_each_other)
            user.isFollowing -> context.getString(R.string.following)
            user.followsYou -> context.getString(R.string.follows_you)
            else -> null
        }
    }
}
