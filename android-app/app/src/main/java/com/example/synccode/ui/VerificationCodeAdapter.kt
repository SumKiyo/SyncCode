package com.example.synccode.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.synccode.data.VerificationCode
import com.example.synccode.databinding.ItemVerificationCodeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class VerificationCodeAdapter(
    private val onCopyClick: (String) -> Unit
) : ListAdapter<VerificationCode, VerificationCodeAdapter.ViewHolder>(DiffCallback) {

    companion object DiffCallback : DiffUtil.ItemCallback<VerificationCode>() {
        private const val PAYLOAD_TIME = "PAYLOAD_TIME"

        override fun areItemsTheSame(oldItem: VerificationCode, newItem: VerificationCode): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: VerificationCode, newItem: VerificationCode): Boolean =
            oldItem == newItem
    }

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshIntervalMs = 30_000L // 每 30 秒刷新一次时间显示

    private val refreshRunnable = object : Runnable {
        override fun run() {
            // 通知所有可见 item 刷新时间文本
            notifyItemRangeChanged(0, currentList.size, PAYLOAD_TIME)
            refreshHandler.postDelayed(this, refreshIntervalMs)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        refreshHandler.postDelayed(refreshRunnable, refreshIntervalMs)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVerificationCodeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TIME)) {
            // 仅刷新时间文本，不重建整个 item
            holder.refreshTime(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class ViewHolder(
        private val binding: ItemVerificationCodeBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: VerificationCode) {
            binding.apply {
                tvAppName.text = item.app
                tvCode.text = item.code.ifEmpty { "—" }
                tvRawText.text = item.rawText
                tvTimestamp.text = formatRelativeTime(item.timestamp)

                root.setOnClickListener {
                    if (item.code.isNotEmpty()) {
                        onCopyClick(item.code)
                    }
                }

                root.setOnLongClickListener {
                    val clipboard = root.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("短信全文", item.rawText))
                    Toast.makeText(root.context, "短信全文已复制", Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }

        fun refreshTime(item: VerificationCode) {
            binding.tvTimestamp.text = formatRelativeTime(item.timestamp)
        }
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)} 分钟前"
            diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)} 小时前"
            diff < TimeUnit.DAYS.toMillis(7) -> "${TimeUnit.MILLISECONDS.toDays(diff)} 天前"
            else -> {
                val sdf = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
}