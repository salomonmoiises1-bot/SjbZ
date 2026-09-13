package com.sjbz.aimp.adapter

import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.sjbz.aimp.R
import com.sjbz.aimp.model.Track
import java.util.Collections

/**
 * Playlist RecyclerView adapter with AIMP styling, Drag & Drop, and Swipe-to-Delete.
 */
class PlaylistAdapter(
    private var tracks: MutableList<Track>,
    private val onItemClick: (Track, Int) -> Unit,
    private val onFavoriteClick: (Track, Int) -> Unit,
    private val onTrackMoved: (fromPosition: Int, toPosition: Int) -> Unit,
    private val onTrackDeleted: (Track, Int) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.TrackViewHolder>() {

    private var currentPlayingIndex: Int = -1

    fun updateData(newTracks: List<Track>, playingIndex: Int = currentPlayingIndex) {
        this.tracks = newTracks.toMutableList()
        this.currentPlayingIndex = playingIndex
        notifyDataSetChanged()
    }

    fun setPlayingIndex(index: Int) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        if (old in tracks.indices) notifyItemChanged(old)
        if (currentPlayingIndex in tracks.indices) notifyItemChanged(currentPlayingIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = tracks[position]
        val isPlaying = position == currentPlayingIndex
        holder.bind(track, isPlaying, position)
    }

    override fun getItemCount(): Int = tracks.size

    inner class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivArtwork: ImageView = itemView.findViewById(R.id.ivTrackArtwork)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvTrackTitle)
        private val tvArtist: TextView = itemView.findViewById(R.id.tvTrackArtist)
        private val tvFormat: TextView = itemView.findViewById(R.id.tvTrackFormat)
        private val tvBitrate: TextView = itemView.findViewById(R.id.tvTrackBitrate)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvTrackDuration)
        private val ivFavorite: ImageView = itemView.findViewById(R.id.ivFavorite)
        private val ivPlayingIndicator: ImageView = itemView.findViewById(R.id.ivPlayingIndicator)
        private val container: View = itemView.findViewById(R.id.trackItemContainer)

        fun bind(track: Track, isPlaying: Boolean, position: Int) {
            tvTitle.text = track.title
            tvArtist.text = track.artist
            tvDuration.text = track.getFormattedDuration()
            tvFormat.text = track.format
            tvBitrate.text = "${track.bitrate}k"

            // Highlight playing track with AIMP signature orange
            if (isPlaying) {
                container.setBackgroundColor(Color.parseColor("#26FF7700"))
                tvTitle.setTextColor(Color.parseColor("#FF8800"))
                ivPlayingIndicator.visibility = View.VISIBLE
            } else {
                container.setBackgroundColor(Color.TRANSPARENT)
                tvTitle.setTextColor(Color.WHITE)
                ivPlayingIndicator.visibility = View.GONE
            }

            // Favorite status
            if (track.isFavorite) {
                ivFavorite.setImageResource(R.drawable.ic_heart_filled)
                ivFavorite.setColorFilter(Color.parseColor("#FF3D00"))
            } else {
                ivFavorite.setImageResource(R.drawable.ic_heart_outline)
                ivFavorite.setColorFilter(Color.parseColor("#666666"))
            }

            ivFavorite.setOnClickListener {
                onFavoriteClick(track, position)
            }

            itemView.setOnClickListener {
                onItemClick(track, position)
            }
        }
    }

    /**
     * ItemTouchHelper Callback for drag-and-drop reordering and swipe-to-delete.
     */
    fun getItemTouchHelper(): ItemTouchHelper {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos in tracks.indices && toPos in tracks.indices) {
                    Collections.swap(tracks, fromPos, toPos)
                    notifyItemMoved(fromPos, toPos)
                    onTrackMoved(fromPos, toPos)
                    return true
                }
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position in tracks.indices) {
                    val deletedTrack = tracks.removeAt(position)
                    notifyItemRemoved(position)
                    onTrackDeleted(deletedTrack, position)
                }
            }
        }
        return ItemTouchHelper(callback)
    }
}
