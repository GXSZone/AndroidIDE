/************************************************************************************
 * This file is part of AndroidIDE.
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
package com.itsaky.androidide.language.java;

import com.itsaky.androidide.lsp.LSPProvider;
import com.itsaky.androidide.utils.Logger;
import com.itsaky.lsp.services.IDELanguageServer;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import com.itsaky.androidide.app.StudioApp;

public class JavaAutoComplete implements AutoCompleteProvider {
    
    private CompletableFuture<Either<List<CompletionItem>, CompletionList>> future;

	@Override
	public List<CompletionItem> getAutoCompleteItems(CharSequence content, String fileUri, String prefix, boolean isInCodeBlock, TextAnalyzeResult colors, int index, int line, int column) throws Exception {
        IDELanguageServer languageServer = LSPProvider.getServerForLanguage(LSPProvider.LANGUAGE_JAVA);
        if(languageServer != null && fileUri != null) {
            
            if(future != null && !future.isDone()) future.cancel(true);
            
            CompletionParams params = new CompletionParams();
            params.setPosition(new Position(line, column));
            params.setTextDocument(new TextDocumentIdentifier(fileUri));
            future = languageServer.getTextDocumentService().completion(params);
            
            if(future.isCancelled()) {
                LOG.debug ("Completion request was cancelled");
                return finalizeResults(new ArrayList<CompletionItem>());
            }
            
            try {
                Either<List<CompletionItem>, CompletionList> either = future.get();
                if(either.isLeft()) {
                    return finalizeResults(either.getLeft());
                }
                
                if(either.isRight()) {
                    return finalizeResults(either.getRight().getItems());
                }
                
            } catch (Throwable th) {
                LOG.error(StudioApp.getInstance().getString(com.itsaky.androidide.R.string.err_completion), th);
            }
        } else {
            LOG.error(StudioApp.getInstance().getString(com.itsaky.androidide.R.string.err_no_server_implementation));
        }
        return new ArrayList<CompletionItem>();
	}
    
    private List<CompletionItem> finalizeResults(List<CompletionItem> items) {
        Collections.sort(items, RESULT_SORTER);
        LOG.debug ("CompletionResults", items);
        return items;
    }
    
    private static final Comparator<CompletionItem> RESULT_SORTER = new Comparator<CompletionItem>(){
        
        @Override
        public int compare(CompletionItem p1, CompletionItem p2) {
            String s1 = p1.getSortText() == null ? p1.getLabel() : p1.getSortText();
            String s2 = p2.getSortText() == null ? p2.getLabel() : p2.getSortText();
            return s1.compareTo(s2);
        }
    };
    
    private static final Logger LOG = Logger.instance("JavaAutoComplete");
}
