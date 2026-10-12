package com.example.gymbro.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatDate
import com.example.gymbro.network.Post

// Fills the MY RESULTS list of the profile: one row (res/layout/item_post.xml) per post.
// Tap a row to see the details, tap the trash can to delete the post.
class PostAdapter(
    private val onClick: (Post) -> Unit,
    private val onDelete: (Post) -> Unit
) : ListAdapter<Post, PostAdapter.PostViewHolder>(POST_DIFF) {

    class PostViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.postIcon)
        val title: TextView = view.findViewById(R.id.postTitle)
        val date: TextView = view.findViewById(R.id.postDate)
        val value: TextView = view.findViewById(R.id.postValue)
        val deleteButton: ImageButton = view.findViewById(R.id.deletePostButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false)
        return PostViewHolder(view)
    }

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        val post = getItem(position)
        holder.icon.setImageResource(post.icon())
        holder.title.text = post.displayTitle(holder.itemView.context)
        holder.date.text = formatDate(post.createdAt)
        holder.value.text = post.displayValue()

        holder.itemView.setOnClickListener { onClick(post) }
        holder.deleteButton.setOnClickListener { onDelete(post) }
    }
}

// Lets the lists of posts animate only the rows that changed
val POST_DIFF = object : DiffUtil.ItemCallback<Post>() {
    override fun areItemsTheSame(old: Post, new: Post) = old.id == new.id
    override fun areContentsTheSame(old: Post, new: Post) = old == new
}
