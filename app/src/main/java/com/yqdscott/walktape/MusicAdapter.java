package com.yqdscott.walktape;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class MusicAdapter extends RecyclerView.Adapter<MusicAdapter.MusicViewHolder> {

    private List<String> musicFiles;
    private OnItemClickListener onItemClickListener;

    public MusicAdapter(List<String> musicFiles, OnItemClickListener onItemClickListener) {
        this.musicFiles = musicFiles;
        this.onItemClickListener = onItemClickListener;
    }

    @NonNull
    @Override
    public MusicViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.music_item, parent, false);
        return new MusicViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MusicViewHolder holder, int position) {
        String[] fileDetails = musicFiles.get(position).split("\n");
        holder.titleTextView.setText(fileDetails[0]);
        holder.filePathTextView.setText(fileDetails[1]);
        holder.itemView.setOnClickListener(v -> onItemClickListener.onItemClick(fileDetails[1]));
    }

    @Override
    public int getItemCount() {
        return musicFiles.size();
    }

    public interface OnItemClickListener {
        void onItemClick(String filePath);
    }

    static class MusicViewHolder extends RecyclerView.ViewHolder {

        TextView titleTextView;
        TextView filePathTextView;

        public MusicViewHolder(@NonNull View itemView) {
            super(itemView);
            titleTextView = itemView.findViewById(R.id.titleTextView);
            filePathTextView = itemView.findViewById(R.id.filePathTextView);
        }
    }
}
