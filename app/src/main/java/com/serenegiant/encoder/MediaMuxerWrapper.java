package com.serenegiant.encoder;
/*
 * AudioVideoRecordingSample
 * Sample project to cature audio and video from internal mic/camera and save as MPEG4 file.
 *
 * Copyright (c) 2014-2015 saki t_saki@serenegiant.com
 *
 * File name: MediaMuxerWrapper.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 * All files in the folder are under this Apache License, Version 2.0.
 */

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.GregorianCalendar;
import java.util.Locale;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.TextUtils;
import android.util.Log;

public class MediaMuxerWrapper {
    private static final boolean DEBUG = false;    // TODO set false on release
    private static final String TAG = "MediaMuxerWrapper";

    private static final String DIR_NAME = "AVRecSample";
    private static final SimpleDateFormat mDateTimeFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US);

    private String mOutputPath;
    private MediaMuxer mMediaMuxer;    // API >= 18
    private int mEncoderCount, mStatredCount;
    private boolean mIsStarted;
    private MediaEncoder mVideoEncoder, mAudioEncoder;

    /**
     * Constructor
     *
     * @param ext extension of output file
     * @throws IOException
     */
    int seg = 100;

    public MediaMuxerWrapper(String ext) throws IOException {
        if (TextUtils.isEmpty(ext)) ext = ".mp4";
        try {
            mOutputPath = getNext();
        } catch (final NullPointerException e) {
            throw new RuntimeException("This app has no permission of writing external storage");
        }
        mMediaMuxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mEncoderCount = mStatredCount = 0;
        mIsStarted = false;
    }

    public String getNext() {
        seg++;
        return Environment.getExternalStorageDirectory() + "/NCAM/video_" + seg + ".mp4";
    }

    public String getOutputPath() {
        return mOutputPath;
    }

    public void prepare() throws IOException {
        mBackgroundThread.start();
        mBackgroundThreadAudio.start();
        mWriterHandler = new Handler(mBackgroundThread.getLooper());
        mWriterHandlerAudio = new Handler(mBackgroundThreadAudio.getLooper());
        mBackgroundThread.setPriority(Thread.MAX_PRIORITY);
        mBackgroundThreadAudio.setPriority(Thread.MAX_PRIORITY);
        if (mVideoEncoder != null)
            mVideoEncoder.prepare();
        if (mAudioEncoder != null)
            mAudioEncoder.prepare();

        circularBufferVideo = new CircularBuffer(mVideoEncoder.mMediaCodec.getOutputFormat(), 5000);
        circularBufferAudio = new CircularBuffer(mAudioEncoder.mMediaCodec.getOutputFormat(), 5000);
    }

    public void startRecording() {
        if (mVideoEncoder != null)
            mVideoEncoder.startRecording();
        if (mAudioEncoder != null)
            mAudioEncoder.startRecording();
    }

    public void stopRecording() {
        if (mVideoEncoder != null)
            mVideoEncoder.stopRecording();
        mVideoEncoder = null;
        if (mAudioEncoder != null)
            mAudioEncoder.stopRecording();
        mAudioEncoder = null;
    }

    public synchronized boolean isStarted() {
        return mIsStarted;
    }

//**********************************************************************
//**********************************************************************

    /**
     * assign encoder to this calss. this is called from encoder.
     *
     * @param encoder instance of MediaVideoEncoder or MediaAudioEncoder
     */
    /*package*/ void addEncoder(final MediaEncoder encoder) {
        if (encoder instanceof MediaVideoEncoder) {
            if (mVideoEncoder != null)
                throw new IllegalArgumentException("Video encoder already added.");
            mVideoEncoder = encoder;
        } else if (encoder instanceof MediaAudioEncoder) {
            if (mAudioEncoder != null)
                throw new IllegalArgumentException("Video encoder already added.");
            mAudioEncoder = encoder;
        } else
            throw new IllegalArgumentException("unsupported encoder");
        mEncoderCount = (mVideoEncoder != null ? 1 : 0) + (mAudioEncoder != null ? 1 : 0);
    }

    /**
     * request start recording from encoder
     *
     * @return true when muxer is ready to write
     */
    /*package*/
    synchronized boolean start() {
        if (DEBUG) Log.v(TAG, "start:");
        mStatredCount++;
        if ((mEncoderCount > 0) && (mStatredCount == mEncoderCount)) {
            mMediaMuxer.start();
            mIsStarted = true;
            notifyAll();
            if (DEBUG) Log.v(TAG, "MediaMuxer started:");
        }
        return mIsStarted;
    }

    /**
     * request stop recording from encoder when encoder received EOS
     */
    /*package*/
    synchronized void stop() {
        if (DEBUG) Log.v(TAG, "stop:mStatredCount=" + mStatredCount);
        mStatredCount--;
        if ((mEncoderCount > 0) && (mStatredCount <= 0)) {
            mMediaMuxer.stop();
            mMediaMuxer.release();
            mIsStarted = false;
            Log.e(TAG, "MediaMuxer stopped:");
        }
    }

    /**
     * assign encoder to muxer
     *
     * @param format
     * @return minus value indicate error
     */
    /*package*/
    synchronized int addTrack(final MediaFormat format) {
        if (mIsStarted)
            throw new IllegalStateException("muxer already started");
        final int trackIx = mMediaMuxer.addTrack(format);
        if (DEBUG)
            Log.i(TAG, "addTrack:trackNum=" + mEncoderCount + ",trackIx=" + trackIx + ",format=" + format);
        return trackIx;
    }

    /**
     * write encoded data to muxer
     *
     * @param trackIndex
     * @param byteBuf
     * @param bufferInfo
     */
    /*package*/
    synchronized void writeSampleData(final int trackIndex, final ByteBuffer byteBuf, final MediaCodec.BufferInfo bufferInfo) {
        if (mStatredCount > 0)
            mMediaMuxer.writeSampleData(trackIndex, byteBuf, bufferInfo);
    }

    private final long mSegmentDurationUsec = 5 * 1000000L;
    private long mLastPresentationTimeUs = -1;
    public HandlerThread mBackgroundThread = new HandlerThread("WriterThreadBackground");
    private Handler mWriterHandler;
    private final Object mCircularBufferFence = new Object();
    private boolean spBusy = false;
    private final Object mCircularBufferChangeSizeFence = new Object();
    private CircularBuffer circularBufferVideo;
    private boolean mCanIncreaseBuffer;

    void writeSampleData(final MediaCodec mediaCodec, final int trackIndex, final int bufferIndex, final ByteBuffer encodedData, final MediaCodec.BufferInfo bufferInfo) {
        synchronized (mCircularBufferFence) {
            int indexTemp = -1;
            while (indexTemp == -1) {
                indexTemp = circularBufferVideo.add(encodedData, bufferInfo); // try to copy the packet to CircularBuffer
                if (indexTemp == -1) {
                    if (mCanIncreaseBuffer) {
                        synchronized (mCircularBufferChangeSizeFence) {
                            mCanIncreaseBuffer = circularBufferVideo.increaseSize(); // try to increase interal buffer
                        }
                        if (mCanIncreaseBuffer) {
                            continue;
                        }
                    }
                    Log.w(TAG, "Blocked until free space is made for track index: " + trackIndex + " before adding package with ts: " + bufferInfo.presentationTimeUs);
                    try {
                        mCircularBufferFence.wait(); // block
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }
        mediaCodec.releaseOutputBuffer(bufferIndex, false); // return the packet to MediaCodec
        mWriterHandler.post(new Runnable() {
            // this runs on the Muxer's writing thread
            @Override
            public void run() {
                MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
                ByteBuffer data = null;
                synchronized (mCircularBufferChangeSizeFence) {
                    data = circularBufferVideo.getTailChunk(mBufferInfo);

                    if (mLastPresentationTimeUs == -1)
                        mLastPresentationTimeUs = (System.currentTimeMillis() / 1000L) + 5;


                    synchronized (this) {
                        if ((System.currentTimeMillis() / 1000L) > mLastPresentationTimeUs) {
                            spBusy = true;
                            mLastPresentationTimeUs = (System.currentTimeMillis() / 1000L) + 5;
                            mMediaMuxer.stop();
                            mMediaMuxer.release();
                            try {
                                mMediaMuxer = new MediaMuxer(getNext(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            mMediaMuxer.addTrack(mVideoEncoder.mMediaCodec.getOutputFormat());
                            mMediaMuxer.addTrack(mAudioEncoder.mMediaCodec.getOutputFormat());
                            mMediaMuxer.start();
                            spBusy = false;
                            Log.e("APP", "WE ARE FREE");
                        }

                        mMediaMuxer.writeSampleData(trackIndex, data, mBufferInfo);
                    }

                }

                synchronized (mCircularBufferFence) {
                    circularBufferVideo.removeTail(); // let CircularBuffer that we are done using the packet
                    mCircularBufferFence.notifyAll();
                }


            }
        });
    }


    public HandlerThread mBackgroundThreadAudio = new HandlerThread("WriterThreadAudio");
    private Handler mWriterHandlerAudio;
    private final Object mCircularBufferFenceAudio = new Object();
    private final Object mCircularBufferChangeSizeFenceAudio = new Object();
    private CircularBuffer circularBufferAudio;
    private boolean mCanIncreaseBufferAudio;

    void writeSampleDataAudio(final MediaCodec mediaCodec, final int trackIndex, final int bufferIndex, final ByteBuffer encodedData, final MediaCodec.BufferInfo bufferInfo) {
        synchronized (mCircularBufferFenceAudio) {
            int indexTemp = -1;
            while (indexTemp == -1) {
                indexTemp = circularBufferAudio.add(encodedData, bufferInfo); // try to copy the packet to CircularBuffer
                if (indexTemp == -1) {
                    if (mCanIncreaseBufferAudio) {
                        synchronized (mCircularBufferChangeSizeFenceAudio) {
                            mCanIncreaseBufferAudio = circularBufferAudio.increaseSize(); // try to increase interal buffer
                        }
                        if (mCanIncreaseBufferAudio) {
                            continue;
                        }
                    }
                    Log.w(TAG, "Blocked until free space is made for track index: " + trackIndex + " before adding package with ts: " + bufferInfo.presentationTimeUs);
                    try {
                        mCircularBufferFenceAudio.wait(); // block
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
            }
        }
        mediaCodec.releaseOutputBuffer(bufferIndex, false); // return the packet to MediaCodec
        mWriterHandlerAudio.post(new Runnable() {
            // this runs on the Muxer's writing thread
            @Override
            public void run() {
                MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();
                ByteBuffer data = null;
                synchronized (mCircularBufferChangeSizeFenceAudio) {
                    data = circularBufferAudio.getTailChunk(mBufferInfo);


                    if (!spBusy) {
                        mMediaMuxer.writeSampleData(trackIndex, data, mBufferInfo);
                    } else {

                        while (spBusy) {
                            Log.e("APP", "WE ARE STOP");
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                        }
                        mMediaMuxer.writeSampleData(trackIndex, data, mBufferInfo);
                    }

                }
                synchronized (mCircularBufferFenceAudio) {
                    circularBufferAudio.removeTail(); // let CircularBuffer that we are done using the packet
                    mCircularBufferFenceAudio.notifyAll();
                }

            }
        });
    }

//**********************************************************************
//**********************************************************************

    /**
     * generate output file
     *
     * @param type Environment.DIRECTORY_MOVIES / Environment.DIRECTORY_DCIM etc.
     * @param ext  .mp4(.m4a for audio) or .png
     * @return return null when this app has no writing permission to external storage.
     */
    public static final File getCaptureFile(final String type, final String ext) {
        final File dir = new File(Environment.getExternalStoragePublicDirectory(type), DIR_NAME);
        Log.d(TAG, "path=" + dir.toString());
        dir.mkdirs();
        if (dir.canWrite()) {
            return new File(dir, getDateTimeString() + ext);
        }
        return null;
    }

    /**
     * get current date and time as String
     *
     * @return
     */
    private static final String getDateTimeString() {
        final GregorianCalendar now = new GregorianCalendar();
        return mDateTimeFormat.format(now.getTime());
    }

}
