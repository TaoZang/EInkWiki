package org.einkwiki.app.reader;

import android.content.Context;
import android.text.Html;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import org.einkwiki.app.R;

import java.util.ArrayList;
import java.util.List;

/** Plain, thumbnail-free search rows to minimize e-ink refresh area. */
public final class SearchResultAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<SearchResult> items = new ArrayList<>();

    public SearchResultAdapter(Context context) {
        inflater = LayoutInflater.from(context);
    }

    public void replace(List<SearchResult> results) {
        items.clear();
        if (results != null) {
            items.addAll(results);
        }
        notifyDataSetChanged();
    }

    public SearchResult itemAt(int position) {
        return items.get(position);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Override
    public SearchResult getItem(int position) {
        return items.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View row = convertView;
        Holder holder;
        if (row == null) {
            row = inflater.inflate(R.layout.item_search_result, parent, false);
            holder = new Holder(
                    row.findViewById(R.id.result_title),
                    row.findViewById(R.id.result_snippet)
            );
            row.setTag(holder);
        } else {
            holder = (Holder) row.getTag();
        }

        SearchResult item = items.get(position);
        holder.title.setText(item.title);
        String plainSnippet = plainText(item.snippet);
        holder.snippet.setText(plainSnippet);
        holder.snippet.setVisibility(plainSnippet.isEmpty() ? View.GONE : View.VISIBLE);
        return row;
    }

    private static String plainText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                .toString()
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static final class Holder {
        final TextView title;
        final TextView snippet;

        Holder(TextView title, TextView snippet) {
            this.title = title;
            this.snippet = snippet;
        }
    }
}
