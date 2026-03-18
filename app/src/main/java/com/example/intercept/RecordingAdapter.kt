package com.example.intercept

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class RecordingAdapter(private var recordings: List<File>, private val onFileClick: (File) -> Unit) : 
    RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val fileName: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val file = recordings[position]
        holder.fileName.text = file.name
        holder.itemView.setOnClickListener { onFileClick(file) }
    }

    override fun getItemCount() = recordings.size

    fun updateData(newDataList: List<File>) {
        recordings = newDataList
        notifyDataSetChanged()
    }
}
