/*
 *   Copyright 2020-2021 Rosemoe
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package io.github.rosemoe.editor.text;

import com.itsaky.androidide.utils.Logger;
import io.github.rosemoe.editor.interfaces.EditorLanguage;
import io.github.rosemoe.editor.struct.BlockLine;
import io.github.rosemoe.editor.struct.Span;
import java.util.List;

/**
 * This is a manager of analyzing text
 *
 * @author Rose
 */
public class TextAnalyzer {

    private static int sThreadId = 0;
    private final RecycleObjContainer mObjContainer = new RecycleObjContainer();
    private final Object mLock = new Object();
    
    public long mOpStartTime;
    private TextAnalyzeResult mResult;
    private Callback mCallback;
    private AnalyzeThread mThread;
    private EditorLanguage mLanguage;

    private static final Logger LOG = Logger.instance("TextAnalyzer");
    
    /**
     * Create a new manager for the given codeAnalyzer
     *
     * @param language The language set to editor.
     */
    public TextAnalyzer(EditorLanguage language) {
        if (language == null || language.getAnalyzer() == null) {
            throw new IllegalArgumentException();
        }
        mResult = new TextAnalyzeResult();
        mResult.addNormalIfNull();
        mLanguage = language;
    }
    
    private synchronized static int nextThreadId() {
        sThreadId++;
        return sThreadId;
    }

    /**
     * Set callback of analysis
     *
     * @param cb New callback
     */
    public void setCallback(Callback cb) {
        mCallback = cb;
    }

    /**
     * Stop the text analyzer
     */
    public void shutdown() {
        final AnalyzeThread thread = mThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            mThread = null;
        }
    }

    /**
     * Called from painting process to recycle outdated objects for reusing
     */
    public void notifyRecycle() {
        mObjContainer.recycle();
    }

    /**
     * Analyze the given text
     *
     * @param origin The source text
     */
    public synchronized void analyze(Content origin) {
        AnalyzeThread thread = this.mThread;
        if (thread == null || !thread.isAlive()) {
            thread = this.mThread = new AnalyzeThread(mLanguage, mLock, origin);
            thread.setName("TextAnalyzeDaemon-" + nextThreadId());
            thread.setDaemon(true);
            thread.start();
        } else {
            thread.restartWith(origin);
            synchronized (mLock) {
                mLock.notify();
            }
        }
    }

    /**
     * Get analysis result
     *
     * @return Result of analysis
     */
    public TextAnalyzeResult getResult() {
        return mResult;
    }

    /**
     * Callback for text analyzing
     *
     * @author Rose
     */
    public interface Callback {

        /**
         * Called when analyze result is available
         * Count of calling this method is not always equal to the count you call {@link TextAnalyzer#analyze(Content)}
         *
         * @param analyzer Host TextAnalyzer
         */
        void onAnalyzeDone(TextAnalyzer analyzer);

    }

    /**
     * Container for objects that is going to be recycled
     *
     * @author Rose
     */
    static class RecycleObjContainer {

        List<List<Span>> spanMap;

        List<BlockLine> blockLines;

        void recycle() {
            ObjectAllocator.recycleBlockLine(blockLines);
            SpanRecycler.getInstance().recycle(spanMap);
            clear();
        }

        void clear() {
            spanMap = null;
            blockLines = null;
        }

    }

    /**
     * AnalyzeThread to control
     */
    public class AnalyzeThread extends Thread {
        
        private final Object lock;
        private volatile boolean waiting = false;
        private Content content;
        private EditorLanguage language;
        
        /**
         * Create a new thread
         *
         * @param a       The CodeAnalyzer to call
         * @param content The Content to analyze
         */
        public AnalyzeThread(EditorLanguage lang, Object lock, Content content) {
            this.language = lang;
            this.lock = lock;
            this.content = content;
        }
        
        @Override
        public void run() {
            try {
                do {
                    TextAnalyzeResult colors = new TextAnalyzeResult();
                    Delegate d = new Delegate();
                    mOpStartTime = System.currentTimeMillis();
                    do {
                        waiting = false;
                        language.getAnalyzer().analyze(language.getLanguageServer(), language.getFile(), content, colors, d);
                        if (waiting) {
                            colors.mSpanMap.clear();
                            colors.mLast = null;
                            colors.mBlocks.clear();
                            colors.mSuppressSwitch = Integer.MAX_VALUE;
                            colors.mLabels = null;
                            colors.mExtra = null;
                        }
                    } while (waiting);

                    mObjContainer.blockLines = mResult.mBlocks;
                    mObjContainer.spanMap = mResult.mSpanMap;
                    mResult = colors;
                    colors.addNormalIfNull();
                    try {
                        if (mCallback != null)
                            mCallback.onAnalyzeDone(TextAnalyzer.this);
                    } catch (NullPointerException e) {
                        e.printStackTrace();
                    }

                    try {
                        synchronized (lock) {
                            lock.wait();
                        }
                    } catch (InterruptedException e) {
                        break;
                    }
                } while (true);
            } catch (Exception ex) {
                LOG.error("An error occurred while analyzing", ex);
            }
        }

        /**
         * New content has been sent
         * Notify us to restart
         *
         * @param content New source
         */
        public synchronized void restartWith(Content content) {
            waiting = true;
            this.content = content;
        }

        /**
         * A delegate for token stream loop
         * To make it stop in time
         */
        public class Delegate {

            /**
             * Whether new input is set
             * If it returns true,you should stop your tokenizing at once
             *
             * @return Whether re-analyze required
             */
            public boolean shouldAnalyze() {
                return !waiting;
            }

        }

    }
}

