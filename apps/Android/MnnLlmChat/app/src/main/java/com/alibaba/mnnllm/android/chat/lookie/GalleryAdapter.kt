// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.chat.lookie

import android.net.Uri
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class GalleryAdapter(private val uris: List<Uri>) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    class VH(val imageView: ImageView) : RecyclerView.ViewHolder(imageView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val size = (parent.context.resources.displayMetrics.density * 200).toInt()
        val iv = ImageView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(size, size).also { it.setMargins(8, 8, 8, 8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.imageView.setImageURI(uris[position])
    }

    override fun getItemCount(): Int = uris.size
}
