package org.lsposed.manager.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.method.LinkMovementMethod;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.caverock.androidsvg.SVG;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.html.HtmlPlugin;
import io.noties.markwon.image.AsyncDrawable;
import io.noties.markwon.image.ImageItem;
import io.noties.markwon.image.ImageSizeResolverDef;
import io.noties.markwon.image.ImagesPlugin;
import io.noties.markwon.image.MediaDecoder;
import io.noties.markwon.image.SchemeHandler;
import io.noties.markwon.image.destination.ImageDestinationProcessor;
import io.noties.markwon.linkify.LinkifyPlugin;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class TextViewUtils {

    // Gap between badges in DP
    private static final int BADGE_GAP_DP = 8;

    // Custom Drawable to handle spacing without stretching the image
    private static class PaddedDrawable extends BitmapDrawable {
        private final int originalWidth;
        public PaddedDrawable(android.content.res.Resources res, Bitmap bitmap, int originalWidth) {
            super(res, bitmap);
            this.originalWidth = originalWidth;
        }
        @Override
        public void draw(@NonNull Canvas canvas) {
            // Draw only the image part, leaving the right side of the bounds empty
            Rect bounds = getBounds();
            int imageWidth = (int) (bounds.height() * ((float) originalWidth / getBitmap().getHeight()));
            Rect dest = new Rect(bounds.left, bounds.top, bounds.left + imageWidth, bounds.bottom);
            canvas.drawBitmap(getBitmap(), null, dest, getPaint());
        }
    }

    public static void setMarkdown(TextView textView, @Nullable String markdown) {
        setMarkdown(textView, markdown, null);
    }

    public static void setMarkdown(final TextView textView, @Nullable String markdown, @Nullable final String sourceUrl) {
        if (markdown == null) {
            textView.setText("");
            return;
        }

        Markwon markwon = Markwon.builder(textView.getContext())
                .usePlugin(HtmlPlugin.create())
                .usePlugin(ImagesPlugin.create(new ImagesPlugin.ImagesConfigure() {
                    @Override
                    public void configureImages(@NonNull ImagesPlugin plugin) {

                        // Unified Decoder: Render at native file size
                        plugin.addMediaDecoder(new MediaDecoder() {
                            @Nullable
                            @Override
                            public Drawable decode(@Nullable String contentType, @NonNull InputStream inputStream) {
                                try {
                                    if ("image/svg+xml".equals(contentType)) {
                                        SVG svg = SVG.getFromInputStream(inputStream);
                                        float w = svg.getDocumentWidth();
                                        float h = svg.getDocumentHeight();
                                        if (w <= 0 || h <= 0) {
                                            if (svg.getDocumentViewBox() != null) {
                                                w = svg.getDocumentViewBox().width();
                                                h = svg.getDocumentViewBox().height();
                                            } else {
                                                w = 24f; h = 24f;
                                            }
                                        }
                                        Bitmap bitmap = Bitmap.createBitmap((int)w, (int)h, Bitmap.Config.ARGB_8888);
                                        svg.renderToCanvas(new Canvas(bitmap));
                                        return new PaddedDrawable(textView.getResources(), bitmap, (int)w);
                                    } else {
                                        Bitmap decoded = BitmapFactory.decodeStream(inputStream);
                                        if (decoded != null) {
                                            return new PaddedDrawable(textView.getResources(), decoded, decoded.getWidth());
                                        }
                                    }
                                } catch (Exception e) { e.printStackTrace(); }
                                return null;
                            }

                            @NonNull
                            @Override
                            public Collection<String> supportedTypes() {
                                return Arrays.asList("image/svg+xml", "image/png", "image/jpeg", "image/webp");
                            }
                        });

                        plugin.addSchemeHandler(new SchemeHandler() {
                            private final OkHttpClient client = new OkHttpClient.Builder().followRedirects(true).build();
                            @NonNull
                            @Override
                            public ImageItem handle(@NonNull String raw, @NonNull Uri uri) {
                                try {
                                    Response response = client.newCall(new Request.Builder().url(raw).build()).execute();
                                    if (response.isSuccessful() && response.body() != null) {
                                        ResponseBody body = response.body();
                                        String contentType = body.contentType() != null ? body.contentType().toString().toLowerCase() : "";
                                        if (contentType.contains("svg") || raw.toLowerCase().contains(".svg") || raw.contains("img.shields.io")) {
                                            contentType = "image/svg+xml";
                                        } else if (contentType.contains(";")) {
                                            contentType = contentType.split(";")[0].trim();
                                        }
                                        return ImageItem.withDecodingNeeded(contentType, body.byteStream());
                                    }
                                } catch (Exception e) { e.printStackTrace(); }
                                return null;
                            }
                            @NonNull
                            @Override
                            public Collection<String> supportedSchemes() { return Arrays.asList("http", "https"); }
                        });
                    }
                }))
                .usePlugin(new AbstractMarkwonPlugin() {
                    @Override
                    public void configureConfiguration(@NonNull MarkwonConfiguration.Builder builder) {
                        builder.imageSizeResolver(new ImageSizeResolverDef() {
                            @NonNull
                            @Override
                            public Rect resolveImageSize(@NonNull AsyncDrawable drawable) {
                                Rect defaultRect = super.resolveImageSize(drawable);
                                float density = textView.getResources().getDisplayMetrics().density;

                                // Scale ALL web pixels to Android DP
                                int width = (int) (defaultRect.width() * density);
                                int height = (int) (defaultRect.height() * density);
                                int gap = (int) (BADGE_GAP_DP * density);

                                int canvasWidth = textView.getWidth() - textView.getPaddingLeft() - textView.getPaddingRight();
                                if (canvasWidth > 0 && width > canvasWidth) {
                                    float ratio = (float) canvasWidth / width;
                                    width = canvasWidth;
                                    height = (int) (height * ratio);
                                    gap = 0; // Remove gap if image takes full width
                                }

                                return new Rect(0, 0, width + gap, height);
                            }
                        });

                        if (sourceUrl != null) {
                            builder.imageDestinationProcessor(new ImageDestinationProcessor() {
                                @NonNull
                                @Override
                                public String process(@NonNull String destination) {
                                    if (Uri.parse(destination).isRelative()) {
                                        String baseUrl = sourceUrl.replace("/github.com/", "/raw.githubusercontent.com/").replace("/tree/", "/");
                                        if (!baseUrl.endsWith("/")) baseUrl += "/";
                                        return Uri.parse(baseUrl).buildUpon().appendEncodedPath(destination).build().toString();
                                    }
                                    return destination;
                                }
                            });
                        }
                    }
                })
                .usePlugin(LinkifyPlugin.create())
                .usePlugin(StrikethroughPlugin.create())
                .build();

        textView.setMovementMethod(LinkMovementMethod.getInstance());
        markwon.setMarkdown(textView, markdown);
    }
}