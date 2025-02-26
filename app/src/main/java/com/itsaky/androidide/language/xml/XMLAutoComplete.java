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
package com.itsaky.androidide.language.xml;

import com.itsaky.androidide.app.StudioApp;
import com.itsaky.androidide.language.xml.completion.XMLCompletionService;
import com.itsaky.androidide.utils.Logger;
import io.github.rosemoe.editor.interfaces.AutoCompleteProvider;
import io.github.rosemoe.editor.text.TextAnalyzeResult;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.eclipse.lsp4j.CompletionItem;

public class XMLAutoComplete implements AutoCompleteProvider {
    
    private static final Logger LOG = Logger.instance("XMLAutoComplete");
    
	private Comparator<CompletionItem> RESULTS_SORTER = new Comparator<CompletionItem>(){

		@Override
		public int compare(CompletionItem p1, CompletionItem p2) {
            if(p1.getSortText() == null && p2.getSortText() != null) return -1;
            if(p1.getSortText() != null && p2.getSortText() == null) return 1;
            if(p1.getSortText() == null && p2.getSortText() == null) return 0;
			return p1.getSortText().compareTo(p2.getSortText());
		}
	};
    
	@Override
	public List<CompletionItem> getAutoCompleteItems(CharSequence content, String fileUri, String prefix, boolean isInCodeBlock, TextAnalyzeResult colors, int index, int line, int column) {
		final XMLCompletionService service = StudioApp.getInstance().getXmlCompletionService();
		return sort(service.complete(content, index, line, column, prefix.toLowerCase(Locale.US).trim()));
	}
	
	private List<CompletionItem> sort(List<CompletionItem> result) {
		Collections.sort(result, RESULTS_SORTER);
		return result;
	}
}
