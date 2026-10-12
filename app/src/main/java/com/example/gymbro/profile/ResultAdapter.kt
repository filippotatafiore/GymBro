package com.example.gymbro.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatDate
import com.example.gymbro.network.PublishedResult

// Fills the MY RESULTS list of the profile: one row (res/layout/item_result.xml) per result
class ResultAdapter : ListAdapter<PublishedResult, ResultAdapter.ResultViewHolder>(DIFF) {

    class ResultViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.resultTitle)
        val date: TextView = view.findViewById(R.id.resultDate)
        val value: TextView = view.findViewById(R.id.resultValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_result, parent, false)
        return ResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: ResultViewHolder, position: Int) {
        val result = getItem(position)
        holder.title.text = result.title
        holder.date.text = formatDate(result.createdAt)
        holder.value.text = result.value
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PublishedResult>() {
            override fun areItemsTheSame(old: PublishedResult, new: PublishedResult) = old.id == new.id
            override fun areContentsTheSame(old: PublishedResult, new: PublishedResult) = old == new
        }
    }
}
