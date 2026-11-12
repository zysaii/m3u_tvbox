package com.lizongying.mytv

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.lizongying.mytv.models.TVViewModel

class CardPresenter(
    private val context: Context,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = object :
            ImageCardView(ContextThemeWrapper(parent.context, R.style.CustomImageCardTheme)) {}

        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val tvViewModel = item as TVViewModel
        val cardView = viewHolder.view as ImageCardView

        cardView.titleText = tvViewModel.getTV().title
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.tag = tvViewModel.videoUrl.value

        // 动态根据 SP 保存的 channelFontSize 设置每个台的字体大小
        val fontSize = SP.channelFontSize
        cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)?.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            fontSize
        )
        cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)?.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            (fontSize - 3f).coerceAtLeast(10f)
        )

        cardView.mainImageView?.let {
            Glide.with(viewHolder.view.context)
                .load(tvViewModel.getTV().logo)
                .centerInside()
                .into(it)
        }

        cardView.setBackgroundColor(Color.WHITE)
        cardView.setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

//        cardView.setOnFocusChangeListener { v, hasFocus ->
//            run {
//                if (hasFocus) {
//                    if (v != null) {
//                        (v as ImageCardView).setInfoAreaBackgroundColor(context.resources.getColor(R.color.focus))
//                    }
//                } else {
//                    if (v != null) {
//                        (v as ImageCardView).setInfoAreaBackgroundColor(context.resources.getColor(R.color.ic_launcher_background))
//                    }
//                }
//            }
//        }

        val epg = tvViewModel.epg.value?.filter { it.beginTime < Utils.getDateTimestamp() }
        if (!epg.isNullOrEmpty()) {
            cardView.contentText = epg.last().title
        } else {
            cardView.contentText = ""
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val TAG = "CardPresenter"
        private const val CARD_WIDTH = 300
        private const val CARD_HEIGHT = 101
    }
}