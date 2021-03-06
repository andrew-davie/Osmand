package net.osmand.plus.views;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.GPXUtilities.WptPt;
import net.osmand.util.Algorithms;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;


public class Renderable {

    // This class handles the actual drawing of segment 'layers'. A segment is a piece of track
    // (i.e., a list of WptPt) which has renders attached to it. There can be any number of renders
    // layered upon each other to give multiple effects.

    static private Timer t = null;                      // fires a repaint for animating segments
    static private int conveyor = 0;                    // single cycler for 'conveyor' style renders
    static private OsmandMapTileView view = null;       // for paint refresh


    // If any render wants to have animation, something needs to make a one-off call to 'startScreenRefresh'
    // to setup a timer to periodically force a screen refresh/redraw

    public static void startScreenRefresh(OsmandMapTileView v, long period) {
        view = v;
        if (t==null && v != null) {
            t = new Timer();
            t.scheduleAtFixedRate(new TimerTask() {
                public void run() {
                    conveyor++;
                    view.refreshMap();
                }
            }, 0, period);
        }
    }


    //----------------------------------------------------------------------------------------------

    public static abstract class RenderableSegment {

        public List<WptPt> points = null;                           // Original list of points
        protected List<WptPt> culled = new ArrayList<>();           // Reduced/resampled list of points
        protected int pointSize;
        protected double segmentSize;

        protected QuadRect trackBounds;
        protected double zoom = -1;
        protected AsynchronousResampler culler = null;                        // The currently active resampler
        protected Paint paint = null;                               // MUST be set by 'updateLocalPaint' before use

        public RenderableSegment(List <WptPt> points, double segmentSize) {
            this.points = points;
            calculateBounds(points);
            this.segmentSize = segmentSize;
        }

        protected void updateLocalPaint(Paint p) {
            if (paint == null) {
                paint = new Paint(p);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(p.getColor());
            paint.setStrokeWidth(p.getStrokeWidth());
        }

        protected void startCuller(double newZoom) {
            if (zoom != newZoom) {
                if (culler != null) {
                    culler.cancel(true);
                }
                culled.clear();
                zoom = newZoom;

                double length = Math.pow(2.0, 17 - zoom) * segmentSize;
                culler = getCuller(length);
                culler.execute("");
            }
        }

        protected AsynchronousResampler getCuller(double segmentSize) {
            return new AsynchronousResampler.GenericResampler(this, segmentSize);
        }

        protected void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {}

        public void drawSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {
            if (QuadRect.trivialOverlap(tileBox.getLatLonBounds(), trackBounds)) { // is visible?
                startCuller(zoom);
                drawSingleSegment(zoom, p, canvas, tileBox);
            }
        }

        private void calculateBounds(List<WptPt> pts) {
            trackBounds = new QuadRect(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
            updateBounds(pts, 0);
        }

        protected void updateBounds(List<WptPt> pts, int startIndex) {
            pointSize = pts.size();
            for (int i = startIndex; i < pointSize; i++) {
                WptPt pt = pts.get(i);
                trackBounds.right = Math.max(trackBounds.right, pt.lon);
                trackBounds.left = Math.min(trackBounds.left, pt.lon);
                trackBounds.top = Math.max(trackBounds.top, pt.lat);
                trackBounds.bottom = Math.min(trackBounds.bottom, pt.lat);
            }
        }

        // When the asynchronous task has finished, it calls this function to set the 'culled' list
        public void setRDP(List<WptPt> cull) {
            culled = cull;
        }

        protected void draw(List<WptPt> pts, Paint p, Canvas canvas, RotatedTileBox tileBox) {

            if (pts.size() > 1) {

                updateLocalPaint(p);
                canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
                QuadRect tileBounds = tileBox.getLatLonBounds();

                WptPt lastPt = pts.get(0);
                float lastx = tileBox.getPixXFromLatLon(lastPt.lat, lastPt.lon);
                float lasty = tileBox.getPixYFromLatLon(lastPt.lat, lastPt.lon);
                boolean last = false;

                int size = pts.size();
                for (int i = 1; i < size; i++) {
                    WptPt pt = pts.get(i);

                    if (Math.min(pt.lon, lastPt.lon) < tileBounds.right && Math.max(pt.lon, lastPt.lon) > tileBounds.left
                            && Math.min(pt.lat, lastPt.lat) < tileBounds.top && Math.max(pt.lat, lastPt.lat) > tileBounds.bottom) {

                        if (!last) {
                            lastx = tileBox.getPixXFromLatLon(lastPt.lat, lastPt.lon);
                            lasty = tileBox.getPixYFromLatLon(lastPt.lat, lastPt.lon);
                            last = true;
                        }

                        float x = tileBox.getPixXFromLatLon(pt.lat, pt.lon);
                        float y = tileBox.getPixYFromLatLon(pt.lat, pt.lon);

                        canvas.drawLine(lastx, lasty, x, y, paint);

                        lastx = x;
                        lasty = y;

                    } else {
                        last = false;
                    }
                    lastPt = pt;
                }
                canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
            }
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class StandardTrack extends RenderableSegment {

        public StandardTrack(List<WptPt> pt, double base) {
            super(pt, base);
        }

        @Override public void startCuller(double newZoom) {

            if (zoom != newZoom) {
                if (culler != null) {
                    culler.cancel(true);
                }
                if (zoom < newZoom) {            // if line would look worse (we're zooming in) then...
                    culled.clear();              // use full-resolution until re-cull complete
                }
                zoom = newZoom;

                double cullDistance = Math.pow(2.0, segmentSize - zoom);    // segmentSize == epsilon
                culler = new AsynchronousResampler.RamerDouglasPeucer(this, cullDistance);
                culler.execute("");
            }
        }

        @Override public void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {
            draw(culled.isEmpty() ? points : culled, p, canvas, tileBox);
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class Altitude extends RenderableSegment {

        public Altitude(List<WptPt> pt, double segmentSize) {
            super(pt, segmentSize);
        }

        @Override protected AsynchronousResampler getCuller(double segmentSize) {
            return new AsynchronousResampler.ResampleAltitude(this, segmentSize);
        }

        @Override public void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {

            if (culled.size() > 1) {
                updateLocalPaint(p);
                canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());

                float bandWidth = paint.getStrokeWidth() * 3;
                paint.setStrokeWidth(bandWidth);

                QuadRect tileBounds = tileBox.getLatLonBounds();

                WptPt lastPt = culled.get(0);
                float lastx = tileBox.getPixXFromLatLon(lastPt.lat, lastPt.lon);
                float lasty = tileBox.getPixYFromLatLon(lastPt.lat, lastPt.lon);
                boolean last = false;

                int size = culled.size();
                for (int i = 1; i < size; i++) {
                    WptPt pt = culled.get(i);

                    if (Math.min(pt.lon, lastPt.lon) < tileBounds.right && Math.max(pt.lon, lastPt.lon) > tileBounds.left
                            && Math.min(pt.lat, lastPt.lat) < tileBounds.top && Math.max(pt.lat, lastPt.lat) > tileBounds.bottom) {

                        if (!last) {
                            lastx = tileBox.getPixXFromLatLon(lastPt.lat, lastPt.lon);
                            lasty = tileBox.getPixYFromLatLon(lastPt.lat, lastPt.lon);
                            last = true;
                        }

                        float x = tileBox.getPixXFromLatLon(pt.lat, pt.lon);
                        float y = tileBox.getPixYFromLatLon(pt.lat, pt.lon);

                        paint.setColor(pt.colourARGB);
                        canvas.drawLine(lastx, lasty, x, y, paint);

                        lastx = x;
                        lasty = y;

                    } else {
                        last = false;
                    }
                    lastPt = pt;
                }
                canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
            }
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class Speed extends Altitude {

        public Speed(List<WptPt> pt, double segmentSize) {
            super(pt, segmentSize);
        }

        @Override protected AsynchronousResampler getCuller(double segmentSize) {
            return new AsynchronousResampler.ResampleSpeed(this, segmentSize);
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class Conveyor extends RenderableSegment {

        public Conveyor(List<WptPt> pt, OsmandMapTileView view, double segmentSize, long refreshRate) {
            super(pt, segmentSize);
            Renderable.startScreenRefresh(view, refreshRate);
        }

        private int getComplementaryColor(int colorToInvert) {
            float[] hsv = new float[3];
            Color.RGBToHSV(Color.red(colorToInvert), Color.green(colorToInvert), Color.blue(colorToInvert), hsv);
            hsv[0] = (hsv[0] + 180) % 360;
            return Color.HSVToColor(hsv);
        }

        @Override public void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {

            if (culled.size() > 1) {
                updateLocalPaint(p);
                canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());

                paint.setColor(getComplementaryColor(p.getColor()));

                QuadRect tileBounds = tileBox.getLatLonBounds();

                WptPt lastPt = culled.get(0);
                float lastx = tileBox.getPixXFromLatLon(lastPt.lat, lastPt.lon);
                float lasty = tileBox.getPixYFromLatLon(lastPt.lat, lastPt.lon);
                boolean last = false;

                int intp = conveyor;

                int size = culled.size();
                for (int i = 1; i < size; i++, intp--) {
                    WptPt pt = culled.get(i);

                    if ((intp & 7) < 3
                            && Math.min(pt.lon, lastPt.lon) < tileBounds.right && Math.max(pt.lon, lastPt.lon) > tileBounds.left
                            && Math.min(pt.lat, lastPt.lat) < tileBounds.top && Math.max(pt.lat, lastPt.lat) > tileBounds.bottom) {

                        if (!last) {
                            lastx = tileBox.getPixXFromLatLon(lastPt.lat, lastPt.lon);
                            lasty = tileBox.getPixYFromLatLon(lastPt.lat, lastPt.lon);
                            last = true;
                        }

                        float x = tileBox.getPixXFromLatLon(pt.lat, pt.lon);
                        float y = tileBox.getPixYFromLatLon(pt.lat, pt.lon);

                        canvas.drawLine(lastx, lasty, x, y, paint);

                        lastx = x;
                        lasty = y;

                    } else {
                        last = false;
                    }
                    lastPt = pt;
                }
                canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
            }
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class DistanceMarker extends RenderableSegment {

        public DistanceMarker(List<WptPt> pt, double segmentSize) {
            super(pt, segmentSize);
        }

        @Override public void startCuller(double zoom) {
            if (culler == null) {
                culler = new AsynchronousResampler.GenericResampler(this, segmentSize);     // once only
                culler.execute("");
            }
        }

        private String getKmLabel(double value) {
            String lab;
            lab = String.format("%d",(int)((value+0.5)/1000));
            return lab;
        }

        @Override public void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {

            if (zoom > 12 && !culled.isEmpty()) {
                updateLocalPaint(p);
                canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());

                float scale = 50;
                float stroke = paint.getStrokeWidth();

                for (int i = culled.size() - 1; --i >= 0; ) {

                    WptPt pt = culled.get(i);
                    float x = tileBox.getPixXFromLatLon(pt.lat, pt.lon);
                    float y = tileBox.getPixYFromLatLon(pt.lat, pt.lon);

                    paint.setTextSize(scale);
                    paint.setStrokeWidth(3);

                    Rect bounds = new Rect();
                    String lab = getKmLabel(pt.getDistance());
                    paint.getTextBounds(lab, 0, lab.length(), bounds);

                    int rectH = bounds.height();
                    int rectW = bounds.width();

                    if (x < canvas.getWidth() + rectW / 2 + scale && x > -rectW / 2 + scale
                            && y < canvas.getHeight() + rectH / 2f && y > -rectH / 2f) {

                        paint.setColor(Color.BLACK);
                        paint.setStrokeWidth(stroke);
                        canvas.drawPoint(x, y, paint);
                        paint.setStrokeWidth(4);
                        canvas.drawText(lab, x - rectW / 2 + 40, y + rectH / 2, paint);
                    }
                    canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
                }
            }
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class Arrows extends RenderableSegment {

        public Arrows(List<WptPt> pt, OsmandMapTileView view, double segmentSize, long refreshRate) {
            super(pt, segmentSize);
            Renderable.startScreenRefresh(view, refreshRate);
        }

        private void drawArrows(int cachedC, Canvas canvas, RotatedTileBox tileBox, boolean internal) {

            float scale = internal ? 0.6f : 1.0f;

            float stroke = paint.getStrokeWidth();
            float arrowSize = 75f;
            boolean broken = true;
            int intp = cachedC;                                // the segment cycler

            float clipL = -arrowSize;
            float clipB = -arrowSize;
            float clipT = canvas.getHeight() + arrowSize;
            float clipR = canvas.getWidth() + arrowSize;

            float lastx = 0;
            float lasty = Float.NEGATIVE_INFINITY;

            int size = culled.size();
            for (int i = 0; i < size; i++, intp--) {
                WptPt pt = culled.get(i);

                float x = tileBox.getPixXFromLatLon(pt.lat, pt.lon);
                float y = tileBox.getPixYFromLatLon(pt.lat, pt.lon);

                boolean nextBroken = true;
                if (Math.min(x, lastx) < clipR && Math.max(x, lastx) > clipL
                        && Math.min(y, lasty) < clipT && Math.max(y, lasty) > clipB) {

                    float segment = intp & 15;
                    if (segment < 5) {

                        paint.setColor(internal ? Algorithms.getRainbowColor(((double) (i)) / ((double) size)) : Color.BLACK);

                        float segpiece = 5 - segment;
                        if (segpiece > 3)
                            segpiece = 3;

                        if (!broken) {
                            float sw = stroke * segpiece * scale;
                            paint.setStrokeWidth(sw);
                            canvas.drawLine(lastx, lasty, x, y, paint);
                        }
                        nextBroken = false;

                        // arrowhead...
                        if (segment == 0 && lasty != Float.NEGATIVE_INFINITY) {
                            float sw = stroke * segpiece * scale;
                            paint.setStrokeWidth(sw);
                            double angle = Math.atan2(lasty - y, lastx - x);

                            float extendx = x - (float) Math.cos(angle) * arrowSize / 2;
                            float extendy = y - (float) Math.sin(angle) * arrowSize / 2;
                            float newx1 = extendx + (float) Math.cos(angle - 0.4) * arrowSize;
                            float newy1 = extendy + (float) Math.sin(angle - 0.4) * arrowSize;
                            float newx2 = extendx + (float) Math.cos(angle + 0.4) * arrowSize;
                            float newy2 = extendy + (float) Math.sin(angle + 0.4) * arrowSize;

                            canvas.drawLine(extendx, extendy, x, y, paint);
                            canvas.drawLine(newx1, newy1, extendx, extendy, paint);
                            canvas.drawLine(newx2, newy2, extendx, extendy, paint);
                        }
                    }
                }
                broken = nextBroken;
                lastx = x;
                lasty = y;
            }
            paint.setStrokeWidth(stroke);
        }

        @Override public void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {

            if (zoom > 13 && !culled.isEmpty()) {
                updateLocalPaint(p);
                canvas.rotate(-tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
                int cachedC = conveyor;
                drawArrows(cachedC, canvas, tileBox, false);
                drawArrows(cachedC, canvas, tileBox, true);
                canvas.rotate(tileBox.getRotate(), tileBox.getCenterPixelX(), tileBox.getCenterPixelY());
            }
        }
    }

    //----------------------------------------------------------------------------------------------

    public static class CurrentTrack extends RenderableSegment {

        public CurrentTrack(List<WptPt> pt) {
            super(pt, 0);
        }

        @Override public void drawSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {
            if (points.size() != pointSize) {
                updateBounds(points, pointSize);
            }
            drawSingleSegment(zoom, p, canvas, tileBox);
        }

        @Override protected void startCuller(double newZoom) {}

        @Override public void drawSingleSegment(double zoom, Paint p, Canvas canvas, RotatedTileBox tileBox) {
            draw(points, p, canvas, tileBox);
        }
    }

}
