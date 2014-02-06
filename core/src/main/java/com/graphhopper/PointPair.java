package com.graphhopper;

import com.graphhopper.util.shapes.GHPlace;

public class PointPair {
    private int id;
    private GHPlace from;
    private GHPlace to;

    public PointPair(int id, GHPlace from, GHPlace to) {
        this.id = id;
        this.from = from;
        this.to = to;
    }

    public int getId() {
        return id;
    }

    public GHPlace getFrom() {
        return from;
    }

    public GHPlace getTo() {
        return to;
    }
}