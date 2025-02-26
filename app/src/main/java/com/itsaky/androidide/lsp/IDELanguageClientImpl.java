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


package com.itsaky.androidide.lsp;

import android.content.DialogInterface;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import androidx.core.content.ContextCompat;
import androidx.transition.ChangeBounds;
import androidx.transition.Fade;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;
import com.blankj.utilcode.util.FileIOUtils;
import com.blankj.utilcode.util.FileUtils;
import com.blankj.utilcode.util.ThreadUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.google.gson.Gson;
import com.itsaky.androidide.EditorActivity;
import com.itsaky.androidide.R;
import com.itsaky.androidide.adapters.DiagnosticsAdapter;
import com.itsaky.androidide.adapters.SearchListAdapter;
import com.itsaky.androidide.app.StudioApp;
import com.itsaky.androidide.databinding.FragmentEditorBinding;
import com.itsaky.androidide.databinding.LayoutDiagnosticInfoBinding;
import com.itsaky.androidide.fragments.EditorFragment;
import com.itsaky.androidide.fragments.sheets.ProgressSheet;
import com.itsaky.androidide.interfaces.EditorActivityProvider;
import com.itsaky.androidide.models.DiagnosticGroup;
import com.itsaky.androidide.models.SearchResult;
import com.itsaky.androidide.tasks.TaskExecutor;
import com.itsaky.androidide.utils.DialogUtils;
import com.itsaky.androidide.utils.LSPUtils;
import com.itsaky.androidide.utils.Logger;
import com.itsaky.lsp.SemanticHighlight;
import com.itsaky.lsp.services.IDELanguageClient;
import com.itsaky.lsp.services.IDELanguageServer;
import com.itsaky.toaster.Toaster;
import io.github.rosemoe.editor.text.Content;
import io.github.rosemoe.editor.widget.CodeEditor;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.LogTraceParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ShowDocumentParams;
import org.eclipse.lsp4j.ShowDocumentResult;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.UnregistrationParams;

/**
 * AndroidIDE specific implementation of the LanguageClient
 */
public class IDELanguageClientImpl implements IDELanguageClient {
    
    protected static final Gson gson = new Gson();
    protected static final Logger LOG = Logger.instance("AbstractLanguageClient");
    public static final int DIAGNOSTIC_TRANSITION_DURATION = 80;
    
    private final Map<File, List<Diagnostic>> diagnostics = new HashMap<>();
    private static IDELanguageClientImpl mInstance;
    
    protected EditorActivityProvider activityProvider;
    private boolean isConnected;
    
    private IDELanguageClientImpl (EditorActivityProvider provider) {
        setActivityProvider(provider);
    }
    
    public static IDELanguageClientImpl getInstance () {
        if (mInstance == null) {
            throw new IllegalStateException ("Client not initialized");
        }
        
        return mInstance;
    }
    
    public static IDELanguageClientImpl initialize (EditorActivityProvider provider) {
        if (mInstance != null) {
            throw new IllegalStateException ("Client is already initialized");
        }
        
        mInstance = new IDELanguageClientImpl (provider);
        
        return getInstance();
    }
    
    public static boolean isInitialized () {
        return mInstance != null;
    }

    public void setActivityProvider(EditorActivityProvider provider) {
        this.activityProvider = provider;
    }

    /**
     * Are we connected to the server?
     */
    public boolean isConnected() {
        return isConnected;
    }
    
    protected EditorActivity activity() {
        if (activityProvider == null) return null;
        return activityProvider.provide();
    }

    @Override
    public void semanticHighlights(SemanticHighlight highlights) {
        final File file = new File(URI.create(highlights.uri));
        final EditorFragment editor = activity().getPagerAdapter().findEditorByFile(file);
        
        if(editor != null) {
            editor.getEditor().setSemanticHighlights(highlights);
        }
    }
    
    public void showDiagnostic(Diagnostic diagnostic, final CodeEditor editor) {
        if(activity() == null || activity().getDiagnosticBinding() == null) {
            hideDiagnostics();
            return;
        }
        
        if(diagnostic == null) {
            hideDiagnostics();
            return;
        }
        
        final LayoutDiagnosticInfoBinding binding = activity().getDiagnosticBinding();
        binding.getRoot().setText(diagnostic.getMessage());
        binding.getRoot().setVisibility(View.VISIBLE);
        
        final float[] cursor = editor.getCursorPosition();

        float x = editor.updateCursorAnchor() - (binding.getRoot().getWidth() / 2);
        float y = activity().getBinding().editorAppBarLayout.getHeight() + (cursor[0] - editor.getRowHeight() - editor.getOffsetY() - binding.getRoot().getHeight());
        binding.getRoot().setX(x);
        binding.getRoot().setY(y);
        activity().positionViewWithinScreen(binding.getRoot(), x, y);
    }

    /**
     * Shows the diagnostic at the bottom of the screen (just above the status text)
     * and requests code actions from language server
     *
     * @param diagnostic The diagnostic to show
     * @param editor The CodeEditor that requested
     */
    public void showDiagnosticAtBottom(final File file, final Diagnostic diagnostic, final CodeEditor editor) {
        if(activity() == null || activity().getPagerAdapter() == null || file == null || diagnostic == null) {
            hideBottomDiagnosticView(file);
            return;
        }
        
        final EditorFragment frag = activity().getPagerAdapter().findEditorByFile(file);
        if(frag == null || frag.getBinding() == null) {
            hideBottomDiagnosticView(file);
            return;
        }
        
        final FragmentEditorBinding binding = frag.getBinding();
        binding.diagnosticTextContainer.setVisibility(View.VISIBLE);
        binding.diagnosticText.setVisibility(View.VISIBLE);
        binding.diagnosticText.setClickable(false);
        binding.diagnosticText.setText(diagnostic.getMessage());
        
        final CompletableFuture <List<Either<Command, CodeAction>>> future = editor.codeActions(Collections.singletonList(diagnostic));
        if(future == null) {
            hideBottomDiagnosticView(file);
            return;
        }
        
        future.whenComplete((a, b) -> {
            final Throwable error = b;
            if(a == null || a.isEmpty()) {
                hideBottomDiagnosticView(file);
                return;
            }
            final List<CodeAction> actions = a.stream().filter(e -> e.isRight()).map (e -> e.getRight()).collect(Collectors.toList());
            if(actions == null || actions.isEmpty()) {
                hideBottomDiagnosticView(file);
                return;
            }
            ThreadUtils.runOnUiThread(() -> {
                final SpannableStringBuilder sb = new SpannableStringBuilder();
                sb.append(activity().getString(R.string.msg_fix_diagnostic), new ForegroundColorSpan(ContextCompat.getColor(activity(), R.color.secondaryColor)), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" ");
                sb.append(diagnostic.getMessage());
                binding.diagnosticText.setText(sb);
                binding.diagnosticText.setClickable(true);
                binding.diagnosticText.setOnClickListener(v -> {
                    showAvailableQuickfixes(editor, actions);
                });
            });
        });
    }
    
    public void hideDiagnostics() {
        if(activity() == null || activity().getDiagnosticBinding() == null) {
            return;
        }
        
        activity().getDiagnosticBinding().getRoot().setVisibility(View.GONE);
    }

    private void hideBottomDiagnosticView(final File file) {
        if(activity() == null || activity().getPagerAdapter() == null || file == null) {
            return;
        }
        
        final EditorFragment frag = activity().getPagerAdapter().findEditorByFile(file);
        if(frag == null || frag.getBinding() == null) {
            return;
        }
        
        frag.getBinding().diagnosticTextContainer.setVisibility(View.GONE);
        frag.getBinding().diagnosticText.setVisibility(View.GONE);
        frag.getBinding().diagnosticText.setClickable(false);
    }
    
    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show signature help in EditorActivity
     */
    public void showSignatureHelp(SignatureHelp signature, File file) {
        if(signature == null || signature.getSignatures() == null) {
            hideSignatureHelp();
            return;
        }
        SignatureInformation info = signatureWithMostParams(signature);
        if(info == null) return;
        activity().getBinding().symbolText.setText(formatSignature(info, signature.getActiveParameter()));
        final EditorFragment frag = activity().getPagerAdapter().findEditorByFile(file);
        if(frag != null) {
            final CodeEditor editor = frag.getEditor();
            final float[] cursor = editor.getCursorPosition();

            float x = editor.updateCursorAnchor() - (activity().getBinding().symbolText.getWidth() / 2);
            float y = activity().getBinding().editorAppBarLayout.getHeight() + (cursor[0] - editor.getRowHeight() - editor.getOffsetY() - activity().getBinding().symbolText.getHeight());
            TransitionManager.beginDelayedTransition(activity().getBinding().getRoot());
            activity().getBinding().symbolText.setVisibility(View.VISIBLE);
            activity().positionViewWithinScreen(activity().getBinding().symbolText, x, y);
        }
    }
    
    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to hide signature help in EditorActivity
     */
    public void hideSignatureHelp() {
        if(activity() == null) return;
        TransitionManager.beginDelayedTransition(activity().getBinding().getRoot());
        activity().getBinding().symbolText.setVisibility(View.GONE);
    }
     
    /**
     * Find the signature with most parameters
     *
     * @param signature The SignatureHelp provided by @{link IDELanguageServer}
     */
    private SignatureInformation signatureWithMostParams(SignatureHelp signature) {
        SignatureInformation signatureWithMostParams = null;
        int mostParamCount = 0;
        final List<SignatureInformation> signatures = signature.getSignatures();
        for(int i=0;i<signatures.size();i++) {
            final SignatureInformation info = signatures.get(i);
            int count = info.getParameters().size();
            if(mostParamCount < count) {
                mostParamCount = count;
                signatureWithMostParams = info;
            }
        }
        return signatureWithMostParams;
    }

    /**
     * Formats (highlights) a method signature
     *
     * @param signature Signature information
     * @param paramIndex Currently active parameter index
     */
    private CharSequence formatSignature(SignatureInformation signature, int paramIndex) {
        String name = signature.getLabel();
        name = name.substring(0, name.indexOf("("));

        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(name, new ForegroundColorSpan(0xffffffff), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append("(", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<ParameterInformation> params = signature.getParameters();
        for(int i=0;i<params.size();i++) {
            int color = i == paramIndex ? 0xffff6060 : 0xffffffff;
            final ParameterInformation info = params.get(i);
            if(i == params.size() - 1) {
                sb.append(info.getLabel().getLeft() + "", new ForegroundColorSpan(color), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                sb.append(info.getLabel().getLeft() + "", new ForegroundColorSpan(color), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(",", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" ");
            }
        }
        sb.append(")", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
        boolean error = params == null || params.getDiagnostics() == null;
        activity().handleDiagnosticsResultVisibility(error || params.getDiagnostics().isEmpty());
        
        if(error) return;
        
        File file = new File(URI.create(params.getUri()));
        if(!file.exists() || !file.isFile()) return;
        
        diagnostics.put(file, params.getDiagnostics());
        activity().getDiagnosticsList().setAdapter(newDiagnosticsAdapter());
        
        EditorFragment editor = null;
        if(activity().getPagerAdapter() != null && (editor = activity().getPagerAdapter().findEditorByFile(file)) != null) {
            editor.setDiagnostics(params.getDiagnostics());
        }
    }
    
    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show locations in EditorActivity
     */
    public void showLocations(List<? extends Location> locations) {
        
        // Cannot show anything if the activity() is null
        if(activity() == null) {
            return;
        }
        
        boolean error = locations == null || locations.isEmpty();
        activity().handleSearchResultVisibility(error);


        if(error) {
            activity().getSearchResultList().setAdapter(new SearchListAdapter(null, null, null));
            return;
        }

        final Map<File, List<SearchResult>> results = new HashMap<>();
        for(int i=0;i<locations.size();i++) {
            try {
                final Location loc = locations.get(i);
                if(loc == null || loc.getUri() == null || loc.getRange() == null) continue;
                final File file = new File(URI.create(loc.getUri()));
                if(!file.exists() || !file.isFile()) continue;
                EditorFragment frag = activity().getPagerAdapter().findEditorByFile(file);
                Content content;
                if(frag != null && frag.getEditor() != null)
                    content = frag.getEditor().getText();
                else content = new Content(null, FileIOUtils.readFile2String(file));
                final List<SearchResult> matches = results.containsKey(file) ? results.get(file) : new ArrayList<>();
                matches.add(
                    new SearchResult(
                        loc.getRange(),
                        file,
                        content.getLineString(loc.getRange().getStart().getLine()),
                        content.subContent(
                            loc.getRange().getStart().getLine(),
                            loc.getRange().getStart().getCharacter(),
                            loc.getRange().getEnd().getLine(),
                            loc.getRange().getEnd().getCharacter()
                        ).toString()
                    )
                );
                results.put(file, matches);
            } catch (Throwable th) {
                
            }
        }

        activity().handleSearchResults(results);
    }

    /**
     * Called by {@link io.github.rosemoe.editor.widget.CodeEditor CodeEditor} to show location links in EditorActivity.
     * These location links are mapped as {@link org.eclipse.lsp4j.Location Location} and then {@link #showLocations(List) } is called.
     */
    public void showLocationLinks(List<? extends LocationLink> locations) {
        
        if(locations == null || locations.size() <= 0) {
            return;
        }
        
        showLocations(locations
            .stream()
                .filter(l -> l != null)
                .map(l -> asLocation(l))
                .filter(l -> l != null)
                .collect(Collectors.toList())
            );
    }
    
    /**
     * Perform the given {@link CodeAction}
     *
     * @param editor The {@link CodeEditor} that invoked the code action request.
     *            This is required to reduce the time finding the code action from the edits.
     * @param action The action to perform
     */
    public void performCodeAction(CodeEditor editor, CodeAction action) {
        if(activity() == null || editor == null || action == null) {
            StudioApp.getInstance().toast(R.string.msg_cannot_perform_fix, Toaster.Type.ERROR);
            return;
        }
        
        final ProgressSheet progress = new ProgressSheet();
        progress.setSubMessageEnabled(false);
        progress.setWelcomeTextEnabled(false);
        progress.setCancelable(false);
        progress.setMessage(activity().getString(R.string.msg_performing_fixes));
        progress.show(activity().getSupportFragmentManager(), "quick_fix_progress");
        
        new TaskExecutor().executeAsyncProvideError(() -> performCodeActionAsync(editor, action), (a, b) -> {
            final Boolean complete = a;
            final Throwable error = b;
            
            progress.dismiss();
            
            if(complete == null || error != null || !complete.booleanValue()) {
                StudioApp.getInstance().toast(R.string.msg_cannot_perform_fix, Toaster.Type.ERROR);
                return;
            }
        });
    }

    /**
     * Usually called {@link CodeEditor} to show a specific document in EditorActivity and select the specified range
     */
    @Override
    public CompletableFuture<ShowDocumentResult> showDocument(ShowDocumentParams params) {
        ShowDocumentResult result = new ShowDocumentResult();
        boolean success = false;
        
        if(activity() == null) {
            result.setSuccess(success);
            return CompletableFuture.completedFuture(result);
        }
        
        if(params != null && params.getUri() != null && params.getSelection() != null) {
            File file = new File(URI.create(params.getUri()));
            if(file.exists() && file.isFile() && FileUtils.isUtf8(file)) {
                final Range range = params.getSelection();
                EditorFragment frag = activity().getPagerAdapter().getFrag(activity().getBinding().tabs.getSelectedTabPosition());
                if(frag != null
                   && frag.getFile() != null
                   && frag.getEditor() != null
                   && frag.getFile().getAbsolutePath().equals(file.getAbsolutePath())) {
                    if(LSPUtils.isEqual(range.getStart(), range.getEnd())) {
                        frag.getEditor().setSelection(range.getStart().getLine(), range.getStart().getCharacter());
                    } else {
                        frag.getEditor().setSelectionRegion(range.getStart().getLine(), range.getStart().getCharacter(), range.getEnd().getLine(), range.getEnd().getCharacter());
                    }
                } else {
                    activity().openFileAndSelect(file, range);
                }
                success = true;
            }
        }
        
        result.setSuccess(success);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public void telemetryEvent(Object params) {
        FirebaseCrashlytics.getInstance().log("LanguageServer[" + this.getClass().getName() + "][TelemetryEvent]\n" + params.toString());
    }

    @Override
    public void logMessage(MessageParams params) {
        // No logs
    }

    @Override
    public void logTrace(LogTraceParams params) {
        
    }
    
    private Location asLocation(LocationLink link) {
        if(link == null || link.getTargetRange() == null || link.getTargetUri() == null) return null;
        final Location location = new Location();
        location.setUri(link.getTargetUri());
        location.setRange(link.getTargetRange());
        return location;
    }

    private List<DiagnosticGroup> mapAsGroup(Map<File, List<Diagnostic>> diags) {
        List<DiagnosticGroup> groups = new ArrayList<>();
        if(diags == null || diags.size() <= 0)
            return groups;
        for(File file : diags.keySet()) {
            List<Diagnostic> fileDiags = diags.get(file);
            if(fileDiags == null || fileDiags.size() <= 0)
                continue;
            DiagnosticGroup group = new DiagnosticGroup(R.drawable.ic_language_java, file, fileDiags);
            groups.add(group);
        }
        return groups;
    }
    
    private void showAvailableQuickfixes (CodeEditor editor, List<CodeAction> actions) {
        final MaterialAlertDialogBuilder builder = DialogUtils.newMaterialDialogBuilder (activity ());
        builder.setTitle(R.string.msg_code_actions);
        builder.setItems(asArray(actions), (d, w) -> {
            final DialogInterface dialog = d;
            final int which = w;
            
            dialog.dismiss();
            hideDiagnostics();
            hideBottomDiagnosticView(editor.getFile());
            performCodeAction(editor, actions.get(which));
        });
        builder.show();
    }

    private CharSequence[] asArray(List<CodeAction> actions) {
        final String[] arr = new String[actions.size()];
        for(int i=0;i<actions.size();i++) {
            arr[i] = actions.get(i).getTitle();
        }
        return arr;
    }
    
    private Boolean performCodeActionAsync(final CodeEditor editor, final CodeAction action) {
        final Map <String, List<TextEdit>> edits = action.getEdit().getChanges();
        if(edits == null || edits.isEmpty()) {
            return Boolean.FALSE;
        }
        
        for(Map.Entry<String, List<TextEdit>> entry : edits.entrySet()) {
            final File file = new File(URI.create(entry.getKey()));
            if(!file.exists()) continue;

            for(TextEdit edit : entry.getValue()) {
                final String editorFilepath = editor.getFile() == null ? "" : editor.getFile().getAbsolutePath();
                if(file.getAbsolutePath().equals(editorFilepath)) {
                    // Edit is in the same editor which requested the code action
                    editInEditor(editor, edit);
                } else {
                    EditorFragment openedFrag = activity().getPagerAdapter().findEditorByFile(file);

                    if(openedFrag != null && openedFrag.getEditor() != null) {
                        // Edit is in another 'opened' file
                        editInEditor(openedFrag.getEditor(), edit);
                    } else {
                        // Edit is in some other file which is not opened
                        // We should open that file and perform the edit
                        openedFrag = activity().openFile(file);
                        if(openedFrag != null && openedFrag.getEditor() != null) {
                            editInEditor(openedFrag.getEditor(), edit);
                        }
                    }
                }
            }
        }

        return Boolean.TRUE;
    }
    
    private void editInEditor (final CodeEditor editor, final TextEdit edit) {
        final Range range = edit.getRange();
        final int startLine = range.getStart().getLine();
        final int startCol = range.getStart().getCharacter();
        final int endLine = range.getEnd().getLine();
        final int endCol = range.getEnd().getCharacter();
        
        activity().runOnUiThread(() -> {
            if(startLine == endLine && startCol == endCol) {
                editor.getText().insert(startLine, startCol, edit.getNewText());
            } else {
                editor.getText().replace(startLine, startCol, endLine, endCol, edit.getNewText());
            }
        });
    }
    
    public DiagnosticsAdapter newDiagnosticsAdapter() {
        return new DiagnosticsAdapter(mapAsGroup(this.diagnostics), activity());
    }
    
    
    /**************************************************
             UNUSED METHODS
    **************************************************/
    
    @Override
    public CompletableFuture<ApplyWorkspaceEditResponse> applyEdit(ApplyWorkspaceEditParams p1) {
        return null;
    }

    @Override
    public CompletableFuture<List<Object>> configuration(ConfigurationParams p1) {
        return null;
    }

    @Override
    public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams p1) {
        return null;
    }

    @Override
    public void notifyProgress(ProgressParams p1) {
    }

    @Override
    public CompletableFuture<Void> refreshCodeLenses() {
        return null;
    }

    @Override
    public CompletableFuture<Void> refreshSemanticTokens() {
        return null;
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams p1) {
        return null;
    }

    @Override
    public void setTrace(SetTraceParams p1) {
    }

    @Override
    public void showMessage(MessageParams p1) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams p1) {
        return null;
    }

    @Override
    public CompletableFuture<Void> unregisterCapability(UnregistrationParams p1) {
        return null;
    }

    @Override
    public CompletableFuture<List<WorkspaceFolder>> workspaceFolders() {
        return null;
    }
}
