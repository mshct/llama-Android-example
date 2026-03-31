package com.example.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView

data class Message(
    val id: String,
    var content: String,
    val isUser: Boolean,
    // only set on assistant messages after generation completes
    var prefillMs: Long = 0,
    var totalMs: Long = 0,
    var tokenCount: Int = 0,
    var tokensPerSec: Double = 0.0
)

class MessageAdapter(
    private val messages: List<Message>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int =
        if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            UserMessageViewHolder(inflater.inflate(R.layout.item_message_user, parent, false))
        } else {
            AssistantMessageViewHolder(inflater.inflate(R.layout.item_message_assistant, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        when (holder) {
            is UserMessageViewHolder -> {
                holder.content.text = message.content
                holder.content.setOnLongClickListener {
                    copyToClipboard(holder.itemView.context, message.content)
                    true
                }
            }
            is AssistantMessageViewHolder -> {
                holder.content.text = message.content
                holder.content.setOnLongClickListener {
                    copyToClipboard(holder.itemView.context, message.content)
                    true
                }
                if (message.tokenCount > 0) {
                    holder.statsRow.visibility = View.VISIBLE
                    holder.speedTv.text = "%.1f tok/s".format(message.tokensPerSec)
                    holder.detailsLink.setOnClickListener {
                        showStatsDialog(holder.itemView.context, message)
                    }
                } else {
                    holder.statsRow.visibility = View.GONE
                }
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun showStatsDialog(context: Context, msg: Message) {
        val text = "Prefill:  %.0f ms\nTotal:    %.2f s\nTokens:   %d\nSpeed:    %.2f tok/s"
            .format(msg.prefillMs.toFloat(), msg.totalMs / 1000.0, msg.tokenCount, msg.tokensPerSec)
        AlertDialog.Builder(context)
            .setTitle("Generation stats")
            .setMessage(text)
            .setPositiveButton("OK", null)
            .show()
    }

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.msg_content)
    }

    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val content: TextView = view.findViewById(R.id.msg_content)
        val statsRow: View = view.findViewById(R.id.stats_row)
        val speedTv: TextView = view.findViewById(R.id.tv_speed_inline)
        val detailsLink: TextView = view.findViewById(R.id.tv_details_link)
    }
}
