package net.osmand.plus.views;

import android.os.AsyncTask;

import net.osmand.plus.GPXUtilities.WptPt;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

public abstract class AsynchronousResampler extends AsyncTask<String,Integer,String> {

    protected Renderable rs;
    protected List<WptPt2> culled = null;
    protected double epsilon;

    AsynchronousResampler(Renderable rs, double epsilon) {
        assert rs != null;
        assert rs.points != null;
        this.rs = rs;
        this.epsilon = epsilon;
    }

    @Override protected void onPostExecute(String result) {
        if (!isCancelled()) {
            rs.setRDP(culled);
        }
    }

    @Override protected String doInBackground(String... params) { return null;}


    private WptPt2 createIntermediatePoint(WptPt lastPt, WptPt pt, double partial, double dist) {
        double angle = Math.atan2(lastPt.lat - pt.lat, lastPt.lon - pt.lon);    // kludge
        return new WptPt2(
                lastPt.lat + (pt.lat - lastPt.lat) * partial,
                lastPt.lon + (pt.lon - lastPt.lon) * partial,
                (long) (lastPt.time + (pt.time - lastPt.time) * partial),
                lastPt.ele + (pt.ele - lastPt.ele) * partial,
                lastPt.speed + (pt.speed - lastPt.speed) * partial,
                dist, angle);
    }

    public List<WptPt2> resampleTrack(List<WptPt> pts, double dist) {

        List<WptPt2> newPts = new ArrayList<>();

        int size = pts.size();
        if (size > 0) {
            WptPt lastPt = pts.get(0);

            double segSub = 0;
            double cumDist = 0;
            for (int i = 1; i < size && !isCancelled(); i++) {
                WptPt pt = pts.get(i);
                double segLength = MapUtils.getDistance(pt.lat, pt.lon, lastPt.lat, lastPt.lon);
                while (segSub < segLength) {
                    double partial = segSub / segLength;
                    newPts.add(createIntermediatePoint(lastPt, pt, partial, cumDist + segLength * partial));
                    segSub += dist;
                }
                segSub -= segLength;
                cumDist += segLength;
                lastPt = pt;
            }
            newPts.add(createIntermediatePoint(lastPt, lastPt, 0, cumDist));
        }
        return newPts;
    }

    protected List<WptPt2> doRamerDouglasPeucerSimplification(List<WptPt> pts) {
        List<WptPt2> rdpTrack = null;
        int nsize = pts.size();
        if (nsize > 0) {
            boolean survivor[] = new boolean[nsize];
            cullRamerDouglasPeucer(survivor, 0, nsize - 1);
            if (!isCancelled()) {
                rdpTrack = new ArrayList<>();
                survivor[0] = true;
                for (int i = 0; i < nsize; i++) {
                    if (survivor[i]) {
                        rdpTrack.add(new WptPt2(pts.get(i)));
                    }
                }
            }
        }
        return rdpTrack;
    }

    private void cullRamerDouglasPeucer(boolean survivor[], int start, int end) {

        double dmax = Double.NEGATIVE_INFINITY;
        int index = -1;

        WptPt startPt = rs.points.get(start);
        WptPt endPt = rs.points.get(end);

        for (int i = start + 1; i < end && !isCancelled(); i++) {
            WptPt pt = rs.points.get(i);
            double d = MapUtils.getOrthogonalDistance(pt.lat, pt.lon, startPt.lat, startPt.lon, endPt.lat, endPt.lon);
            if (d > dmax) {
                dmax = d;
                index = i;
            }
        }
        if (dmax > epsilon) {
            cullRamerDouglasPeucer(survivor, start, index);
            cullRamerDouglasPeucer(survivor, index, end);
        } else {
            survivor[end] = true;
        }
    }

    public static class Generic extends AsynchronousResampler {

        public Generic(Renderable rs, double epsilon) {
            super(rs, epsilon);
        }

        @Override protected String doInBackground(String... params) {
            culled = resampleTrack(rs.points, epsilon);
            return null;
        }
    }

    public static class RamerDouglasPeucer extends AsynchronousResampler {

        public RamerDouglasPeucer(Renderable rs, double epsilon) {
            super(rs, epsilon);
        }

        @Override protected String doInBackground(String... params) {
            culled = doRamerDouglasPeucerSimplification(rs.points);
            return null;
        }
    }

    public static class Altitude extends AsynchronousResampler {

        public Altitude(Renderable rs, double epsilon) {
            super(rs, epsilon);
        }

        @Override protected String doInBackground(String... params) {
            culled = doRamerDouglasPeucerSimplification(rs.points); //resampleTrack(rs.points, epsilon);
            if (!isCancelled() && !culled.isEmpty()) {

                int halfC = Algorithms.getRainbowColor(0.5);

                // Calculate the absolutes of the altitude variations
                Double max = culled.get(0).ele;
                Double min = max;
                for (WptPt2 pt : culled) {
                    max = Math.max(max, pt.ele);
                    min = Math.min(min, pt.ele);
                    pt.colourARGB = halfC;                  // default, in case there are no 'ele' in GPX
                }

                Double elevationRange = max - min;
                if (elevationRange > 0)
                    for (WptPt2 pt : culled)
                        pt.colourARGB = Algorithms.getRainbowColor((pt.ele - min) / elevationRange);
            }

            return null;
        }
    }

    public static class Speed extends AsynchronousResampler {

        public Speed(Renderable rs, double epsilon) {
            super(rs, epsilon);
        }

        @Override protected String doInBackground(String... params) {

            // Resample track, then analyse speeds and set colours for each point

            culled = doRamerDouglasPeucerSimplification(rs.points); //resampleTrack(rs.points, epsilon);
            if (!isCancelled() && !culled.isEmpty()) {

                WptPt2 lastPt = culled.get(0);
                lastPt.speed = 0;

                int size = culled.size();
                for (int i = 1; i < size; i++) {
                    WptPt2 pt = culled.get(i);
                    double delta = pt.time - lastPt.time;
                    pt.speed = delta > 0 ? MapUtils.getDistance(pt.lat, pt.lon,
                            lastPt.lat, lastPt.lon) / delta : 0;
                    lastPt = pt;
                }

                if (size > 1) {
                    culled.get(0).speed = culled.get(1).speed;      // fixup 1st speed
                }

                double max = lastPt.speed;
                double min = max;

                int halfC = Algorithms.getRainbowColor(0.5);

                for (WptPt2 pt : culled) {
                    max = Math.max(max, pt.speed);
                    min = Math.min(min, pt.speed);
                    pt.colourARGB = halfC;                  // default, in case there are no 'time' in GPX
                }
                double speedRange = max - min;
                if (speedRange > 0) {
                    for (WptPt2 pt : culled)
                        pt.colourARGB = Algorithms.getRainbowColor((pt.speed - min) / speedRange);
                }
            }
            return null;
        }
    }
}
