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


package com.itsaky.androidide.adapters;

import android.content.res.Resources;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.blankj.utilcode.util.ThreadUtils;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.itsaky.androidide.R;
import com.itsaky.androidide.app.StudioApp;
import com.itsaky.androidide.databinding.LayoutCompletionItemBinding;
import com.itsaky.androidide.utils.Logger;
import com.itsaky.androidide.utils.TypefaceUtils;
import com.itsaky.apiinfo.ApiInfo;
import com.itsaky.apiinfo.models.ClassInfo;
import com.itsaky.apiinfo.models.FieldInfo;
import com.itsaky.apiinfo.models.Info;
import com.itsaky.apiinfo.models.MethodInfo;
import io.github.rosemoe.editor.widget.EditorCompletionAdapter;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnGlobalLayoutListener;

public class CompletionListAdapter extends EditorCompletionAdapter {
    
    private static final Logger LOG = Logger.instance("CompletionListAdapter");
    
    @Override
    public int getItemHeight() {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, Resources.getSystem().getDisplayMetrics());
    }

    @Override
    protected View getView(int position, View convertView, ViewGroup parent, boolean isCurrentCursorPosition) {
        final LayoutCompletionItemBinding binding = LayoutCompletionItemBinding.inflate(LayoutInflater.from(getContext()), parent, false);

		CompletionItem item = getItem(position);
		String label = item.getLabel(), desc = item.getDetail(), type = item.getKind().toString();
		String header = type == null || type.length() <= 0 ? "O" : String.valueOf(type.toString().charAt(0));
		
        binding.completionIconText.setText(header);
        binding.completionLabel.setText(label);
        binding.completionType.setText(type);
        binding.completionDetail.setText(desc);
        binding.completionIconText.setTypeface(TypefaceUtils.jetbrainsMono(), Typeface.BOLD);
		if (desc == null || desc.isEmpty())
			binding.completionDetail.setVisibility(View.GONE);

        if (isCurrentCursorPosition)
            binding.getRoot().setBackgroundColor(ContextCompat.getColor(getContext(), R.color.completionList_backgroundSelected));
        else binding.getRoot().setBackgroundColor(ContextCompat.getColor(getContext(), R.color.completionList_background));

        binding.completionApiInfo.setVisibility(View.GONE);
        
        showApiInfoIfNeeded(item, binding.completionApiInfo);
        
        binding.getRoot().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            ViewGroup.LayoutParams p = binding.completionIconText.getLayoutParams();
            p.height = binding.getRoot().getHeight();
            binding.completionIconText.setLayoutParams(p);
        });
        
        return binding.getRoot();
    }

    private void showApiInfoIfNeeded(final CompletionItem item, final TextView completionApiInfo) {
        
        new Thread(() -> {
            final ApiInfo apiInfo = StudioApp.getInstance().apiInfo();
            boolean hasRead = apiInfo != null && apiInfo.hasRead();
            boolean isValid = isValidForApiVersion(item);
            
            if (hasRead && isValid) {

                JsonElement element = new Gson().toJsonTree(item.getData());
                if (element == null || !element.isJsonObject()) return;
                JsonObject data = element.getAsJsonObject();
                if (!data.has("className")) return;
                
                final String className = data.get("className").getAsString();
                CompletionItemKind kind = item.getKind();
                
                ClassInfo clazz = apiInfo.getClassByName(className);
                if(clazz == null) return;
                
                Info info = clazz;

                /**
                 * If this Info is not a class info, find the right member
                 */
                if (kind == CompletionItemKind.Method
                    && data.has("erasedParameterTypes")
                    && data.has("memberName")) {
                    JsonElement erasedParameterTypesElement = data.get("erasedParameterTypes");
                    if(erasedParameterTypesElement.isJsonArray()) {
                        String simpleName = data.get("memberName").getAsString();
                        JsonArray erasedParameterTypes = erasedParameterTypesElement.getAsJsonArray();
                        String[] paramTypes = new String[erasedParameterTypes.size()];
                        for(int i=0;i<erasedParameterTypes.size();i++) {
                            paramTypes[i] = erasedParameterTypes.get(i).getAsString();
                        }
                        
                        MethodInfo method = clazz.getMethod(simpleName, paramTypes);
                        
                        if(method != null) {
                            info = method;
                        }
                    }
                } else if(kind == CompletionItemKind.Field
                          && data.has("memberName")) {
                    String simpleName = data.get("memberName").getAsString();
                    FieldInfo field = clazz.getFieldByName(simpleName);
                    
                    if(field != null) {
                        info = field;
                    }
                }

                final StringBuilder infoBuilder = new StringBuilder();
                if (info != null && info.since > 1) {
                    infoBuilder.append(completionApiInfo.getContext().getString(R.string.msg_api_info_since, info.since));
                    infoBuilder.append("\n");
                }

                if (info != null && info.removed > 0) {
                    infoBuilder.append(completionApiInfo.getContext().getString(R.string.msg_api_info_removed, info.removed));
                    infoBuilder.append("\n");
                }

                if (info != null && info.deprecated > 0) {
                    infoBuilder.append(completionApiInfo.getContext().getString(R.string.msg_api_info_deprecated, info.deprecated));
                    infoBuilder.append("\n");
                }
                
                ThreadUtils.runOnUiThread(() -> {
                    if(infoBuilder.toString().trim().length() > 0) {
                        completionApiInfo.setText(infoBuilder.toString().trim());
                        completionApiInfo.setVisibility(View.VISIBLE);
                    } else completionApiInfo.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private boolean isValidForApiVersion(CompletionItem item) {
        if (item == null) return false;
        final CompletionItemKind type = item.getKind();
        JsonElement element = new Gson().toJsonTree(item.getData());
        if (
        
        /**
         * These represent a class type
         */
        (type == CompletionItemKind.Class
            || type == CompletionItemKind.Interface
            || type == CompletionItemKind.Enum
            
        /**
         * These represent a method type
         */
            || type == CompletionItemKind.Method
            || type == CompletionItemKind.Constructor
            
        /**
         * A field type
         */
            || type == CompletionItemKind.Field)
            
            && element != null && element.isJsonObject()) {
            JsonObject data = element.getAsJsonObject();
            return data.has("className");
        }
        return false;
    }
}
