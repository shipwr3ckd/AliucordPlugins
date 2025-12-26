/*
 * Ven's Aliucord Plugins
 * Copyright (C) 2021 Vendicated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
*/

package dev.vendicated.aliucordplugins.betterplatformembeds

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.*
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.widget.LinearLayout
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import com.aliucord.Logger
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.wrappers.embeds.MessageEmbedWrapper
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemEmbed
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.card.MaterialCardView
import java.util.*

class ScrollableWebView(ctx: Context) : WebView(ctx) {
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        requestDisallowInterceptTouchEvent(true)
        return super.onTouchEvent(event)
    }
}

@AliucordPlugin
class PlayableEmbeds : Plugin() {
    companion object {
        val Logger = Logger(PlayableEmbeds::class.simpleName!!)
    }

    private val webviewMap = WeakHashMap<WebView, String>()
    private val widgetId = View.generateViewId()
    private val spotifyUrlRe = Regex("https://open\\.spotify\\.com/(\\w+)/(\\w+)")
    private val youtubeUrlRe =
        Regex(
            "(?:https?://)?(?:(?:www|m)\\.)?(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/" +
                    "(?:embed/|v/|watch\\?v=|watch\\?.+&v=|shorts/))((\\w|-){11})" +
                    "(?:(?:\\?|&)(?:star)?t=(\\d+))?(?:\\S+)?"
        )
    private val youtubeClipRe =
        Regex(
            "(?:https?://)?(?:(?:www|m)\\.)?(?:youtu\\.be/|youtube(?:-nocookie)?\\.com/clip/)" +
                    "((\\w|-){36})(?:(?:\\?|&)(?:star)?t=(\\d+))?(?:\\S+)?"
        )

    private val embedImageContainer = Utils.getResId("embed_image_container", "id")
    private val chatListItemEmbedImage = Utils.getResId("chat_list_item_embed_image", "id")
    private val chatListItemEmbedImageIcons = Utils.getResId("chat_list_item_embed_image_icons", "id")
    private val chatListItemEmbedContainerCard = Utils.getResId("chat_list_item_embed_container_card", "id")

    override fun start(context: Context) {
        patcher.after<WidgetChatListAdapterItemEmbed>("configureUI", WidgetChatListAdapterItemEmbed.Model::class.java) {
            val model = it.args[0] as WidgetChatListAdapterItemEmbed.Model
            val embed = MessageEmbedWrapper(model.embedEntry.embed)
            val holder = it.thisObject as WidgetChatListAdapterItemEmbed
            val layout = holder.itemView as ConstraintLayout

            layout.findViewById<WebView?>(widgetId)?.let { v ->
                if (webviewMap[v] == embed.url) return@after
                (v.parent as ViewGroup).removeView(v)
            }
            val url = embed.url ?: return@after
            when (embed.provider?.name) {
                "YouTube" -> addYoutubeEmbed(layout, url)
                "Spotify" -> addSpotifyEmbed(layout, url)
                else -> addDefaultEmbed(layout, embed)
            }
        }
    }

    private fun addDefaultEmbed(layout: ViewGroup, embed: MessageEmbedWrapper) {
        if (!settings.getBool("genericEnabled", true))
            return

        val videoUrl = embed.video?.url ?: return

        val ctx = layout.context
        val cardView = layout.findViewById<CardView>(embedImageContainer)
        val chatListItemEmbedImage = cardView.findViewById<SimpleDraweeView>(chatListItemEmbedImage)
        val playButton = cardView.findViewById<View>(chatListItemEmbedImageIcons)
        playButton.visibility = View.GONE
        chatListItemEmbedImage.visibility = View.GONE
        val posterUrl = embed.thumbnail?.proxyUrl

        val webView = ScrollableWebView(ctx).apply {
            id = widgetId
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = false

            cardView.addView(this)
        }

        webviewMap[webView] = embed.url
        webView.run {
            val needsPreload = if (posterUrl != null) "none" else "metadata"
            loadData(
                """
                <html>
                    <body style="margin: 0; padding: 0;">
                        <video poster="$posterUrl" preload="$needsPreload" autoplay="false" controls style="width: 100%">
                            <source src="$videoUrl" type="video/mp4">
                        </video>
                    </body>
                </html>
                """,
                "text/html",
                "UTF-8"
            )
        }
    }

    private fun addYoutubeEmbed(layout: ViewGroup, url: String) {
        if (!settings.getBool("youtubeEnabled", true))
            return

        val ctx = layout.context
        val videoId: String
        val clipId: String
        val timestamp: String
        val res = youtubeUrlRe.find(url, 0)
        if (res?.groupValues?.isNotEmpty() == true) {
            videoId = res.groupValues[1]
            timestamp = res.groupValues[3]
        } else {
            val clipRes = youtubeClipRe.find(url, 0)
            if (clipRes?.groupValues?.isNotEmpty() == true) {
                clipId = clipRes.groupValues[1]
                try {
                    addYoutubeClipEmbed(layout, url, clipId)
                    return
                } catch (exception: Exception) {
                    Logger.error("Failed to add clip embed from $url", exception)
                    return
                }
            } else {
                Logger.debug("no match found")
                return
            }
        }

        val cardView = layout.findViewById<CardView>(embedImageContainer)
        val chatListItemEmbedImage = cardView.findViewById<SimpleDraweeView>(chatListItemEmbedImage)
        val playButton = cardView.findViewById<View>(chatListItemEmbedImageIcons)
        playButton.visibility = View.GONE
        chatListItemEmbedImage.visibility = View.GONE

        val webView = ScrollableWebView(ctx).apply {
            id = widgetId
            setBackgroundColor(Color.TRANSPARENT)
            // val maxImgWidth = EmbedResourceUtils.INSTANCE.computeMaximumImageWidthPx(ctx)
            // val (width, height) = EmbedResourceUtils.INSTANCE.calculateScaledSize(1280, 720, maxImgWidth, maxImgWidth, ctx.resources, maxImgWidth / 2)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true

            cardView.addView(this)
        }
        webviewMap[webView] = url

        webView.run {
            val ytUrl = "https://piped.private.coffee/embed/$videoId?start=$timestamp"
            loadData(
                """
                <html>
                    <head>
                        <style>
                            body {
                                margin: 0;
                                padding: 0;
                            }
                            .wrapper {
                                position: relative;
                                padding-bottom: 56.25%; /* (100% / 16 * 9), makes the div 16x9 */
                                height: 0;
                                overflow: hidden;
                            }
                            .wrapper iframe {
                                position: absolute;
                                top: 0; 
                                left: 0;
                                width: 100%;
                                height: 100%;
                            }
                        </style>
                    </head>
                    <body>
                        <div class="wrapper">
                            <iframe
                                src="$ytUrl"
                                title="YouTube video player"
                                frameborder="0"
                                allow="clipboard-write; encrypted-media"
                            />
                        </div>
                    </body>
                </html>
                """,
                "text/html",
                "UTF-8"
            )
        }
    }

    private fun addYoutubeClipEmbed(layout: ViewGroup, url: String, clipId: String) {
        if (!settings.getBool("youtubeClipsEnabled", true))
            return

        val ctx = layout.context
        val cardView = layout.findViewById<CardView>(embedImageContainer)
        val chatListItemEmbedImage = cardView.findViewById<SimpleDraweeView>(chatListItemEmbedImage)
        val playButton = cardView.findViewById<View>(chatListItemEmbedImageIcons)
        playButton.visibility = View.GONE
        chatListItemEmbedImage.visibility = View.GONE

        val webView = ScrollableWebView(ctx).apply {
            id = widgetId
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.allowContentAccess = true

            cardView.addView(this)
        }
        webviewMap[webView] = url

        val endpointLink = settings.getString("yaoiEndpoint", Settings.DEFAULT_ENDPOINT)
        webView.run {
            loadData(
                """
                <html>
                    <head>
                        <style>
                            body {
                                margin: 0;
                                padding: 0;
                            }
                            .wrapper {
                                position: relative;
                                padding-bottom: 56.25%; /* (100% / 16 * 9), makes the div 16x9 */
                                height: 0;
                                overflow: hidden;
                            }
                            .wrapper iframe {
                                position: absolute;
                                top: 0; 
                                left: 0;
                                width: 100%;
                                height: 100%;
                            }
                        </style>
                        <script src="https://cdn.jsdelivr.net/npm/protobufjs@7.X.X/dist/light/protobuf.min.js"></script>
                        <script>
                            var getJSON = function(url, callback) {
                                var xhr = new XMLHttpRequest();
                                xhr.open('GET', url, true);
                                xhr.responseType = 'json';
                                xhr.onload = function() {
                                    var status = xhr.status;
                                    if (status === 200) {
                                        callback(null, xhr.response);
                                    } else {
                                        callback(status, xhr.response);
                                    }
                                };
                                xhr.send();
                            };
                            
                            var loadClip = function() {
                                var response = getJSON("$endpointLink/videos?part=id,clip&clipId=$clipId", function(err, data) {
                                    if (err == null) {
                                        var item = data.items[0];
                                        var clip = item.clip;
                                        var url = "https://www.youtube.com/embed/" + item.videoId + "?clip=" + "$clipId" + "&clipt=" + getClipT(clip.startTimeMs, clip.endTimeMs);
                                        document.getElementById("ytIframe").src = url;                                 
                                    } else {
                                        document.getElementById("debug").innerText = "Error " + err;   
                                    }
                                });
                            }
                            
                            var getClipT = function(startTimeMs, endTimeMs) {
                                var root = protobuf.Root.fromJSON(
                                    {
                                        "nested": {
                                            "ClipTime": {
                                                "fields": {
                                                    "startTimeMs": {
                                                        "type": "int32",
                                                        "id": 2
                                                    },
                                                    "endTimeMs": {
                                                        "type": "int32",
                                                        "id": 3
                                                    }
                                                }
                                            }
                                        }
                                    }
                                );
                                var ClipTime = root.lookup("ClipTime");
                                var test = ClipTime.create({startTimeMs: startTimeMs, endTimeMs: endTimeMs});
                                var encoded = ClipTime.encode(test).finish();
                                var base64 = protobuf.util.base64.encode(encoded, 0, encoded.length)
                                return base64
                            }
                        </script>
                    </head>
                    <body onload="loadClip()">
                        <p id="debug" style="position: fixed; color: white" />
                        <div class="wrapper">
                            <iframe
                                src=""
                                id="ytIframe"
                                title="YouTube video player"
                                frameborder="0"
                                allow="clipboard-write; encrypted-media"
                            />
                        </div>
                    </body>
                </html>
                """,
                "text/html",
                "UTF-8"
            )
        }
    }

    private fun addSpotifyEmbed(layout: ViewGroup, url: String) {
        if (!settings.getBool("spotifyEnabled", true))
            return

        val ctx = layout.context

        val (_, type, itemId) = spotifyUrlRe.find(url, 0).groupValues
        val embedUrl = "https://open.spotify.com/embed/$type/$itemId"

        val webView = (if (type == "track") WebView(ctx) else ScrollableWebView(ctx)).apply {
            id = widgetId
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            @SuppressLint("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true

            val cardView = layout.findViewById<MaterialCardView>(chatListItemEmbedContainerCard)
            cardView.addView(this)
        }

        webviewMap[webView] = url
        webView.run {
            loadData(
                """
                <html>
                    <body style="margin: 0; padding: 0;">
                        <iframe
                            src="$embedUrl"
                            width="100%"
                            height="${if (type == "track") 80 else 380}"
                            frameborder="0"
                            allow="encrypted-media"
                            allowtransparency
                        />
                    </body>
                </html>
                """,
                "text/html",
                "UTF-8"
            )
        }
    }

    @Suppress("Unused")
    private fun addPornHubEmbed() {

    }

    override fun stop(context: Context) {
        patcher.unpatchAll()
    }

    init {
        settingsTab = SettingsTab(Settings::class.java, SettingsTab.Type.BOTTOM_SHEET).withArgs(settings)
    }
}
