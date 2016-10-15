/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.wearable;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;


import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchfaceService extends CanvasWatchFaceService {
    private static final Typeface LIGHT_TYPEFACE =
            Typeface.create("sans-serif-light", Typeface.NORMAL);

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = 500;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchfaceService.Engine> mWeakReference;

        public EngineHandler(SunshineWatchfaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchfaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        boolean mAmbient;
        boolean mShouldDrawColons;

        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mMinutesPaint;
        Paint mColonPaint;
        Paint mDateTextPaint;

        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mTimeXOffset;
        float mTimeYOffset;
        float mDateXOffset;
        float mDateYOffset;
        float mLineYOffset;
        float mColonWidth;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchfaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchfaceService.this.getResources();
            mTimeYOffset = resources.getDimension(R.dimen.time_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHourPaint = new Paint();
            mHourPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mMinutesPaint = new Paint();
            mMinutesPaint = createTextPaint(resources.getColor(R.color.digital_text), LIGHT_TYPEFACE);

            mColonPaint = new Paint();
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE);

            mDateTextPaint = new Paint();
            mDateTextPaint = createTextPaint(resources.getColor(R.color.date_text), LIGHT_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchfaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchfaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchfaceService.this.getResources();
            boolean isRound = insets.isRound();

            //time vars setup
            mTimeXOffset = resources.getDimension(R.dimen.time_x_offset);
            float timeTextSize = resources.getDimension(R.dimen.time_text_size);

            mHourPaint.setTextSize(timeTextSize);
            mMinutesPaint.setTextSize(timeTextSize);
            mColonPaint.setTextSize(timeTextSize);

            //date vars setup
            mDateXOffset = resources.getDimension(R.dimen.date_x_offset);
            float dateTextSize = resources.getDimension(R.dimen.date_text_size);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);
            mDateTextPaint.setTextSize(dateTextSize);

            mColonWidth = mColonPaint.measureText(":");
            mLineYOffset = getResources().getDimension(R.dimen.line_top);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHourPaint.setAntiAlias(!inAmbientMode);
                    mMinutesPaint.setAntiAlias(!inAmbientMode);
                    mColonPaint.setAntiAlias(!inAmbientMode);
                    mDateTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;
            if (mShouldDrawColons) {
                Log.d("Should", "draw colons");

            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String hourText = String.format(Locale.getDefault(), "%02d",
                    mCalendar.get(Calendar.HOUR_OF_DAY));
            String minText = String.format(Locale.getDefault(), "%02d",
                    mCalendar.get(Calendar.MINUTE));

            canvas.drawText(hourText, mTimeXOffset, mTimeYOffset, mHourPaint);
            float x = mTimeXOffset + mHourPaint.measureText(hourText);
            if (isInAmbientMode() || mShouldDrawColons) {
                canvas.drawText(":", x, mTimeYOffset, mColonPaint);
            }
            x += mColonWidth;
            canvas.drawText(minText, x, mTimeYOffset, mMinutesPaint);

            if (!isInAmbientMode()) {
                String dateText = new SimpleDateFormat(
                        "EEE, MMM dd YYYY",
                        Locale.getDefault()).format(mCalendar.getTime()
                ).toUpperCase();

                canvas.drawText(dateText, mDateXOffset, mDateYOffset, mDateTextPaint);

                float lineLength = getResources().getDimension(R.dimen.line_length);
                float lineLeft = (bounds.width() / 2) - (lineLength / 2);
                float lineRight = lineLeft + lineLength;

                canvas.drawRect(lineLeft, mLineYOffset, lineRight, mLineYOffset + 1, mDateTextPaint);

                Bitmap defaultBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);
                Rect src = new Rect(0, 0, defaultBitmap.getWidth(), defaultBitmap.getHeight());
                Rect dst = new Rect((int) mDateXOffset, 180, 100, 220);
                canvas.drawBitmap(defaultBitmap, src, dst, null);

                Paint weatherPaint = new Paint();
                weatherPaint.setTextSize(30);
                weatherPaint.setAntiAlias(true);
                weatherPaint.setColor(getResources().getColor(R.color.digital_text));
                canvas.drawText("25°", mDateXOffset + 50, 210, weatherPaint);
                weatherPaint.setColor(getResources().getColor(R.color.date_text));
                canvas.drawText("16°", mDateXOffset + 100, 210, weatherPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }
    }
}
