/************************************************************************************
 * This file is part of AndroidIDE.
 *
 *  
 *
 * AndroidIDE is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * AndroidIDE is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 *
**************************************************************************************/


package com.itsaky.androidide;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import com.blankj.utilcode.util.ClipboardUtils;
import com.blankj.utilcode.util.KeyboardUtils;
import com.blankj.utilcode.util.SizeUtils;
import com.blankj.utilcode.util.ThrowableUtils;
import com.itsaky.androidide.app.StudioActivity;
import com.itsaky.androidide.databinding.ActivityTerminalBinding;
import com.itsaky.androidide.models.ConstantsBridge;
import com.itsaky.androidide.utils.Environment;
import com.itsaky.androidide.utils.Logger;
import com.itsaky.androidide.utils.TypefaceUtils;
import com.itsaky.androidide.views.virtualkeys.SpecialButton;
import com.itsaky.androidide.views.virtualkeys.VirtualKeyButton;
import com.itsaky.androidide.views.virtualkeys.VirtualKeysConstants;
import com.itsaky.androidide.views.virtualkeys.VirtualKeysInfo;
import com.itsaky.androidide.views.virtualkeys.VirtualKeysView;
import com.itsaky.terminal.TerminalEmulator;
import com.itsaky.terminal.TerminalSession;
import com.itsaky.terminal.TerminalSessionClient;
import com.itsaky.terminal.TextStyle;
import com.itsaky.terminal.view.TerminalView;
import com.itsaky.terminal.view.TerminalViewClient;
import java.util.Map;
import org.json.JSONException;
import android.os.Bundle;

public class TerminalActivity extends StudioActivity {
    
    private ActivityTerminalBinding binding;
    private TerminalView terminal;
    private TerminalSession session;
    
    private boolean isVisible = false;
    private int currentTextSize = 0;
    
    private KeyListener listener;
    private final Client client = new Client();
    private static final Logger LOG = Logger.instance("TerminalActivity");
    
    public static final String KEY_WORKING_DIRECTORY = "terminal_workingDirectory";
    
    @Override
    protected View bindLayout() {
        binding = ActivityTerminalBinding.inflate(getLayoutInflater());
        terminal = new TerminalView(this, null);
        terminal.setTerminalViewClient(client);
        terminal.attachSession(createSession(getWorkingDirectory()));
        terminal.setKeepScreenOn(true);
        terminal.setTextSize(currentTextSize = SizeUtils.dp2px(10));
        terminal.setTypeface(TypefaceUtils.jetbrainsMono());
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, 0);
        params.weight = 1f;
        
        binding.getRoot().addView(terminal, 0, params);
        try {
            binding.virtualKeyTable.setVirtualKeysViewClient(getKeyListener());
            binding.virtualKeyTable.reload(new VirtualKeysInfo(ConstantsBridge.VIRTUAL_KEYS, "", VirtualKeysConstants.CONTROL_CHARS_ALIASES));
        } catch (JSONException e) {
            // Won't happen
        }

        return binding.getRoot();
    }

    private String getWorkingDirectory() {
        final Bundle extras = getIntent().getExtras();
        if(extras != null && extras.containsKey(KEY_WORKING_DIRECTORY)) {
            String directory = extras.getString(KEY_WORKING_DIRECTORY, null);
            if(directory == null || directory.trim().length() <= 0) {
                directory = Environment.HOME.getAbsolutePath();
            }
            return directory;
        }
        return Environment.HOME.getAbsolutePath();
    }
    
    private KeyListener getKeyListener() {
        return listener == null ? listener = new KeyListener(terminal) : listener;
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        setTerminalCursorBlinkingState(true);
    }

    @Override
    protected void onStart() {
        super.onStart();
        isVisible = true;
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        isVisible = false;
        setTerminalCursorBlinkingState(false);
    }
    
    public boolean isVisible() {
        return isVisible;
    }
    
    private void setTerminalCursorBlinkingState(boolean start ) {
        if(terminal != null && terminal.mEmulator != null) {
            terminal.setTerminalCursorBlinkerState(start, true);
        }
    }
    
    private TerminalSession createSession(final String workingDirectory) {
        final Map<String, String> envs = Environment.getEnvironment(true);
        final String[] env = new String[envs.size()];
        int i=0;
        for(Map.Entry<String, String> entry : envs.entrySet()) {
            env[i] = entry.getKey() + "=" + entry.getValue();
            i++;
        }
        
        session = new TerminalSession(
            Environment.SHELL.getAbsolutePath(), // Shell command
            workingDirectory, // Working directory
            new String[]{}, // Arguments
            env, // Environment variables
            TerminalEmulator.DEFAULT_TERMINAL_TRANSCRIPT_ROWS, // Transcript rows
            client // TerminalSessionClient
        );
        
        return session;
    }
    
    
    
    
    
    
    
    private final class KeyListener implements VirtualKeysView.IVirtualKeysView {
        
        private final TerminalView terminal;

        public KeyListener(TerminalView terminal) {
            this.terminal = terminal;
        }
        
        @Override
        public void onVirtualKeyButtonClick(View view, VirtualKeyButton buttonInfo, Button button) {
            if(terminal == null) return;
            if (buttonInfo.isMacro()) {
                String[] keys = buttonInfo.getKey().split(" ");
                boolean ctrlDown = false;
                boolean altDown = false;
                boolean shiftDown = false;
                boolean fnDown = false;
                for (String key : keys) {
                    if (SpecialButton.CTRL.getKey().equals(key)) {
                        ctrlDown = true;
                    } else if (SpecialButton.ALT.getKey().equals(key)) {
                        altDown = true;
                    } else if (SpecialButton.SHIFT.getKey().equals(key)) {
                        shiftDown = true;
                    } else if (SpecialButton.FN.getKey().equals(key)) {
                        fnDown = true;
                    } else {
                        onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
                        ctrlDown = false; altDown = false; shiftDown = false; fnDown = false;
                    }
                }
            } else {
                onTerminalExtraKeyButtonClick(view, buttonInfo.getKey(), false, false, false, false);
            }
        }

        protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
            if (VirtualKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
                Integer keyCode = VirtualKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.get(key);
                if (keyCode == null) return;
                int metaState = 0;
                if (ctrlDown) metaState |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                if (altDown) metaState |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
                if (shiftDown) metaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
                if (fnDown) metaState |= KeyEvent.META_FUNCTION_ON;

                KeyEvent keyEvent = new KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState);
                terminal.onKeyDown(keyCode, keyEvent);
            } else {
                // not a control char
                for(int off=0;off<key.length();) {
                    int codePoint = key.codePointAt(off);
                    terminal.inputCodePoint(codePoint, ctrlDown, altDown);
                    off += Character.charCount(codePoint);
                }
            }
        }

        @Override
        public boolean performVirtualKeyButtonHapticFeedback(View view, VirtualKeyButton buttonInfo, Button button) {
            // No need to handle this
            // VirtualKeysView will take care of performing haptic feedback
            return false;
        }
    }
    
    private final class Client implements TerminalViewClient, TerminalSessionClient {
        
        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            return false;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            if(keyCode == KeyEvent.KEYCODE_ENTER && !session.isRunning()) {
                finish();
                return true;
            }
            return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            return false;
        }

        @Override
        public void onTextChanged(TerminalSession changedSession) {
            terminal.onScreenUpdated();
        }

        @Override
        public void onTitleChanged(TerminalSession changedSession) {
        }

        @Override
        public void onSessionFinished(TerminalSession finishedSession) {
            finish();
        }

        @Override
        public void onCopyTextToClipboard(TerminalSession session, String text) {
            ClipboardUtils.copyText("AndroidIDE Terminal", text);
        }

        @Override
        public void onPasteTextFromClipboard(TerminalSession session) {
            String clip = ClipboardUtils.getText().toString();
            if(clip != null && clip.trim().length() >= 0) {
                if(terminal != null && terminal.mEmulator != null) {
                    terminal.mEmulator.paste(ClipboardUtils.getText().toString());
                }
            }
        }

        @Override
        public void onBell(TerminalSession session) {
        }

        @Override
        public void onColorsChanged(TerminalSession session) {
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
        }

        @Override
        public Integer getTerminalCursorStyle() {
            return TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE;
        }

        @Override
        public float onScale(float scale) {
            return 1.0f;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            KeyboardUtils.showSoftInput(terminal);
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return false;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return true;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            return false;
        }

        @Override
        public boolean readControlKey() {
            Boolean state = binding.virtualKeyTable.readSpecialButton(SpecialButton.CTRL, true);
            return state != null && state.booleanValue();
        }

        @Override
        public boolean readAltKey() {
            Boolean state = binding.virtualKeyTable.readSpecialButton(SpecialButton.ALT, true);
            return state != null && state.booleanValue();
        }

        @Override
        public boolean readFnKey() {
            return false;
        }

        @Override
        public boolean readShiftKey() {
            return false;
        }

        @Override
        public void onEmulatorSet() {
            setTerminalCursorBlinkingState(true);
            
            if(session != null) {
                binding.getRoot().setBackgroundColor(session.getEmulator().mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
            }
        }

        @Override
        public void logError(String tag, String message) {
            // No logs
        }

        @Override
        public void logWarn(String tag, String message) {
            
        }

        @Override
        public void logInfo(String tag, String message) {
            
        }

        @Override
        public void logDebug(String tag, String message) {
            
        }

        @Override
        public void logVerbose(String tag, String message) {
            
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            
        }
    }
}
