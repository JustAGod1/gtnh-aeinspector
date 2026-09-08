package com.aeinspector.gui;

import net.minecraft.nbt.NBTTagCompound;

/** Decode once when a snapshot arrives, not in the rendering loop. */
final class GraphSeries {
    final int id;
    final String name;
    final boolean fluid;
    final long start, width;
    final long[][] counts = new long[4][];
    final long[] observed;
    private GraphSeries(GraphSeries source,long start,int size) {
        id=source.id; name=source.name; fluid=source.fluid; width=source.width; this.start=start;
        observed=new long[size];
        for(int c=0;c<4;c++) counts[c]=new long[size];
    }
    GraphSeries(NBTTagCompound tag) {
        id = tag.getInteger("id"); name = tag.getString("name"); fluid = tag.getBoolean("fluid");
        start = tag.getLong("start"); width = tag.getLong("width");
        if (start < 0 || width <= 0) throw new IllegalArgumentException("Invalid graph interval");
        observed = InspectorData.longs(tag.getByteArray("observed"));
        for (int c = 0; c < 4; c++) {
            counts[c] = InspectorData.longs(tag.getByteArray("c" + c));
            if (counts[c].length != observed.length) throw new IllegalArgumentException("Mismatched graph arrays");
        }
    }
    double time(int bucket) { return start + (bucket + 0.5) * width; }
    int bucket(double tick) { return (int) Math.floor((tick - start) / width); }
    double rate(int direction, int bucket) {
        return observed[bucket] == 0 ? Double.NaN : ((double) counts[direction][bucket] + counts[direction + 2][bucket]) * 1200 / observed[bucket];
    }
    /** Keep the still-visible left edge across several responses arriving during one animation. */
    static GraphSeries retain(GraphSeries older,GraphSeries current,double earliest) {
        if(older==null||older.width!=current.width||older.start>=current.start) return current;
        long start=Math.max(older.start,(long)Math.floor(Math.max(0,earliest)/current.width)*current.width);
        start=Math.min(start,current.start);
        long size=(current.start-start)/current.width+current.observed.length;
        if(size>600) return current; // a long observation gap must not allocate an enormous client array
        GraphSeries joined=new GraphSeries(current,start,(int)size);
        for(int i=0;i<joined.observed.length;i++) {
            long tick=start+i*current.width;
            GraphSeries source=tick>=current.start?current:older;
            int bucket=source.bucket(tick);
            if(bucket<0||bucket>=source.observed.length) continue;
            joined.observed[i]=source.observed[bucket];
            for(int c=0;c<4;c++) joined.counts[c][i]=source.counts[c][bucket];
        }
        return joined;
    }
}
