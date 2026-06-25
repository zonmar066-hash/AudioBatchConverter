package com.glenn.audioconverter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.glenn.audioconverter.databinding.ItemLogBinding

class LogAdapter(
    private val items: MutableList<BatchConverter.ConversionResult> = mutableListOf(),
    private val onLongClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemLogBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvFileName.text = item.originalName
        holder.binding.tvStatus.text = if (item.success) "✅ ${item.message}" else "❌ ${item.message}"

        holder.binding.root.setBackgroundColor(
            if (item.success) Color.argb(30, 76, 175, 80)
            else Color.argb(30, 244, 67, 54)
        )

        holder.binding.root.setOnLongClickListener {
            onLongClick?.invoke(position)
            true
        }
    }

    override fun getItemCount() = items.size

    fun addItem(result: BatchConverter.ConversionResult) {
        items.add(result)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun getAllLogs(): String {
        return items.joinToString("\n") {
            (if (it.success) "✅" else "❌") + " ${it.originalName}: ${it.message}"
        }
    }
}
