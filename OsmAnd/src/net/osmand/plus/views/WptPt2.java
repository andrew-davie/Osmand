package net.osmand.plus.views;

import net.osmand.plus.GPXUtilities;

/**
 * While this is essentially a copy of the WptPt class, it is implemented as a new class for memory
 * efficiency. The addition of a number of variables (colourARGB, speed, distance, angle) specifically
 * for the different renderers is not efficiently held in the WptPt class if they are not used.
 * Similarly, there are a number of variables in the WptPt class which are not required for the
 * renderers to use, and so savings are made in both directions.
 * Technically one could combine WptPt and WptPt2 but that would be pretty silly, and completely
 * misunderstanding the whole POINT of the above comment.
 */

public class WptPt2 {

    public double lat;
    public double lon;
    public long time = 0;
    public double ele = Double.NaN;             // m
    public double speed = 0;                    // km/h
    public int colourARGB = 0;					// point colour (used for altitude/speed colouring)
    public double distance = 0.0;				// cumulative distance, if in a track
    public double angle = 0;

//    public WptPt2(double lat, double lon) {
//        this.lat = lat;
//        this.lon = lon;
//    }

    public WptPt2(GPXUtilities.WptPt pt)  {
        lat = pt.lat;
        lon = pt.lon;
        time = pt.time;
        ele = pt.ele;
        speed = pt.speed;
        angle = 0;
    }

    public WptPt2(double lat, double lon, long time, double ele, double speed, double distance, double angle) {
        this.lat = lat;
        this.lon = lon;
        this.time = time;
        this.ele = ele;
        this.speed = speed;
        this.distance = distance;
        this.angle = angle;
    }
}
