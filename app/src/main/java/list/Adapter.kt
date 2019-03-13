/*
Copyright (C) 2019 Matthew Chandler

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
the Software, and to permit persons to whom the Software is furnished to do so,
subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER
IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
*/

package org.mattvchandler.progressbars.list

import android.content.Intent
import android.databinding.ViewDataBinding
import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import org.mattvchandler.progressbars.Progress_bars
import org.mattvchandler.progressbars.databinding.ProgressBarRowBinding
import org.mattvchandler.progressbars.databinding.SingleProgressBarRowBinding
import org.mattvchandler.progressbars.db.DB
import org.mattvchandler.progressbars.db.Data
import org.mattvchandler.progressbars.db.Progress_bars_table
import org.mattvchandler.progressbars.settings.Settings
import org.mattvchandler.progressbars.util.Notification_handler
import java.io.Serializable
import java.security.InvalidParameterException

private typealias Stack<T> = MutableList<T>

private fun <T> Stack<T>.pop(): T
{
    val t = this.last()
    this.removeAt(this.size - 1)
    return t
}

private fun <T> Stack<T>.push(t: T)
{
    this.add(t)
}

private data class Undo_event(val type: Undo_event.Type, val data: Data?, val pos: Int?, val old_pos: Int?): Serializable
{
    enum class Type{ ADD, REMOVE, EDIT, MOVE }
}

// keeps track of timer GUI rows
class Adapter(private val activity: Progress_bars): RecyclerView.Adapter<Adapter.Holder>()
{
    private val data_list: MutableList<View_data>
    private val undo_stack: Stack<Undo_event> = mutableListOf()
    private val redo_stack: Stack<Undo_event> = mutableListOf()

    // an individual row object
    inner class Holder(private val row_binding: ViewDataBinding): RecyclerView.ViewHolder(row_binding.root), View.OnClickListener
    {
        private var moved_from_pos = 0
        internal lateinit var data: View_data

        fun bind(data: View_data)
        {
            this.data = data

            when(row_binding)
            {
                is ProgressBarRowBinding -> row_binding.data = data
                is SingleProgressBarRowBinding -> row_binding.data = data
            }
        }

        fun update() = data.update_display(activity.resources)

        // click the row to edit its data
        override fun onClick(v: View)
        {
            // create and launch an intent to launch the editor, and pass the rowid
            val intent = Intent(activity, Settings::class.java)
            intent.putExtra(Settings.EXTRA_EDIT_DATA, data)
            activity.startActivityForResult(intent, Progress_bars.RESULT_EDIT_DATA)
        }

        // called when dragging during each reordering
        fun on_move(to: Holder) = move_item(adapterPosition, to.adapterPosition)
        // called when a row is selected for deletion or reordering
        fun on_selected() { moved_from_pos = adapterPosition }

        // called when a row is released from reordering
        fun on_cleared()
        {
            if(adapterPosition != RecyclerView.NO_POSITION && adapterPosition != moved_from_pos)
            {
                redo_stack.clear()
                undo_stack.push(Undo_event(Undo_event.Type.MOVE, null, adapterPosition, moved_from_pos))
            }
        }
    }

    init
    {
        setHasStableIds(true)

        // update and alarms
        Data.apply_all_repeats(activity)
        Notification_handler.reset_all_alarms(activity)

        val db = DB(activity).readableDatabase
        val cursor = db.rawQuery(Progress_bars_table.SELECT_ALL_ROWS, null)

        cursor.moveToFirst()
        data_list = (0 until cursor.count).map{ val data = View_data(activity, Data(cursor)); cursor.moveToNext(); data }.toMutableList()

        cursor.close()
        db.close()
    }

    override fun onCreateViewHolder(parent_in: ViewGroup, viewType: Int): Holder
    {
        // create a new row
        val inflater = LayoutInflater.from(activity)
        val row_binding = when(viewType)
        {
            SEPARATE_TIME_VIEW -> ProgressBarRowBinding.inflate(inflater, parent_in, false)
            SINGLE_TIME_VIEW -> SingleProgressBarRowBinding.inflate(inflater, parent_in, false)
            else -> throw(InvalidParameterException("Unknown viewType: $viewType"))
        }
        val holder = Holder(row_binding)
        row_binding.root.setOnClickListener(holder)
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int)
    {
        // move to the requested position and bind the data
        holder.bind(data_list[position])
    }

    override fun getItemViewType(position: Int): Int
    {
        return if(data_list[position].separate_time)
            SEPARATE_TIME_VIEW
        else
            SINGLE_TIME_VIEW
    }

    override fun getItemId(position: Int) = data_list[position].rowid
    override fun getItemCount() = data_list.size

    fun find_by_rowid(rowid: Long) = data_list.indexOfFirst{ it.rowid == rowid}

    fun apply_repeat(rowid: Long)
    {
        val pos = find_by_rowid(rowid)
        if(pos < 0)
            return

        data_list[pos].apply_repeat()
        Notification_handler.reset_alarm(activity, Data(data_list[pos]))
        data_list[pos].reinit(activity)
    }

    // called when a row is deleted
    fun on_item_dismiss(pos: Int)
    {
        redo_stack.clear()
        undo_stack.push(Undo_event(Undo_event.Type.REMOVE, Data(data_list[pos]), pos, null))
        activity.invalidateOptionsMenu()

        remove_item(pos)
    }

    fun set_edited(data: Data)
    {
        redo_stack.clear()

        val pos = if(data.rowid >= 0)
        {
            val pos = find_by_rowid(data.rowid)
            undo_stack.push(Undo_event(Undo_event.Type.EDIT, Data(data_list[pos]), pos, null))
            edit_item(data, pos)
            pos
        }
        else
        {
            val pos = data_list.size
            data.insert(DB(activity).writableDatabase, pos.toLong()) // insert to set rowid
            add_item(data, pos)
            undo_stack.push(Undo_event(Undo_event.Type.ADD, null, pos, null))
            pos
        }

        activity.invalidateOptionsMenu()
    }

    private fun add_item(data: Data, pos: Int)
    {
        data.apply_repeat()
        Notification_handler.reset_alarm(activity, data)
        data_list.add(pos, View_data(activity, data))
        notifyItemInserted(pos)
        activity.scroll_to(pos)
    }
    private fun edit_item(data: Data, pos: Int)
    {
        data.apply_repeat()
        Notification_handler.reset_alarm(activity, data)
        data_list[pos] = View_data(activity, data)
        notifyItemChanged(pos)
        activity.scroll_to(pos)
    }
    private fun remove_item(pos: Int)
    {
        Notification_handler.cancel_alarm(activity, data_list[pos])
        data_list.removeAt(pos)
        notifyItemRemoved(pos)
        activity.scroll_to(pos - 1)
    }

    private fun move_item(from_pos: Int, to_pos: Int)
    {
        val moved = data_list.removeAt(from_pos)
        data_list.add(to_pos, moved)

        notifyItemMoved(from_pos, to_pos)
        activity.scroll_to(to_pos)
    }

    fun can_undo() = !undo_stack.isEmpty()
    fun can_redo() = !redo_stack.isEmpty()

    fun undo() = undo_redo(undo_stack, redo_stack)
    fun redo() = undo_redo(redo_stack, undo_stack)

    private fun undo_redo(stack: Stack<Undo_event>, inverse_stack: Stack<Undo_event>)
    {
        if(stack.isEmpty())
            return

        val event = stack.pop()
        when(event.type)
        {
            Undo_event.Type.ADD ->
            {
                inverse_stack.push(Undo_event(Undo_event.Type.REMOVE, Data(data_list[event.pos!!]), event.pos, null))
                remove_item(event.pos)
            }
            Undo_event.Type.REMOVE ->
            {
                inverse_stack.push(Undo_event(Undo_event.Type.ADD, null, event.pos, null))
                add_item(event.data!!, event.pos!!)
            }
            Undo_event.Type.EDIT ->
            {
                inverse_stack.push(Undo_event(Undo_event.Type.EDIT, Data(data_list[event.pos!!]), event.pos, null))
                edit_item(event.data!!, event.pos)
            }
            Undo_event.Type.MOVE ->
            {
                inverse_stack.push(Undo_event(Undo_event.Type.MOVE, null, event.old_pos!!, event.pos!!))
                move_item(event.pos, event.old_pos)
            }
        }
        activity.invalidateOptionsMenu()
    }

    var undo_redo_stacks
    get() = Pair(undo_stack, redo_stack) as Serializable
    set(stack_pair)
    {
        val (new_undo_stack, new_redo_stack) = stack_pair as Pair<*, *>

        undo_stack.clear()
        redo_stack.clear()

        @Suppress("UNCHECKED_CAST")
        undo_stack.addAll(new_undo_stack as Stack<Undo_event>)
        @Suppress("UNCHECKED_CAST")
        redo_stack.addAll(new_redo_stack as Stack<Undo_event>)
    }

    fun save_to_db()
    {
        val db = DB(activity).writableDatabase
        db.beginTransaction()
        db.delete(Progress_bars_table.TABLE_NAME, null, null)

        for(i in 0 until data_list.size)
            data_list[i].insert(db, i.toLong())

        db.setTransactionSuccessful()
        db.endTransaction()
        db.close()
    }

    companion object
    {
        private const val SEPARATE_TIME_VIEW = 0
        private const val SINGLE_TIME_VIEW = 1
    }
}
