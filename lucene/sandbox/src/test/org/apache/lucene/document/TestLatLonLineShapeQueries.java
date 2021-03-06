/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.document;

import com.carrotsearch.randomizedtesting.generators.RandomNumbers;
import org.apache.lucene.document.LatLonShape.QueryRelation;
import org.apache.lucene.geo.EdgeTree;
import org.apache.lucene.geo.GeoTestUtil;
import org.apache.lucene.geo.GeoUtils;
import org.apache.lucene.geo.Line;
import org.apache.lucene.geo.Line2D;
import org.apache.lucene.geo.Polygon2D;
import org.apache.lucene.index.PointValues.Relation;

import static org.apache.lucene.geo.GeoUtils.MAX_LON_INCL;
import static org.apache.lucene.geo.GeoUtils.MIN_LON_INCL;

/** random bounding box and polygon query tests for random generated {@link Line} types */
public class TestLatLonLineShapeQueries extends BaseLatLonShapeTestCase {

  protected final LineValidator VALIDATOR = new LineValidator();

  @Override
  protected ShapeType getShapeType() {
    return ShapeType.LINE;
  }

  @Override
  protected Line randomQueryLine(Object... shapes) {
    if (random().nextInt(100) == 42) {
      // we want to ensure some cross, so randomly generate lines that share vertices with the indexed point set
      int maxBound = (int)Math.floor(shapes.length * 0.1d);
      if (maxBound < 2) {
        maxBound = shapes.length;
      }
      double[] lats = new double[RandomNumbers.randomIntBetween(random(), 2, maxBound)];
      double[] lons = new double[lats.length];
      for (int i = 0, j = 0; j < lats.length && i < shapes.length; ++i, ++j) {
        Line l = (Line) (shapes[i]);
        if (random().nextBoolean() && l != null) {
          int v = random().nextInt(l.numPoints() - 1);
          lats[j] = l.getLat(v);
          lons[j] = l.getLon(v);
        } else {
          lats[j] = GeoTestUtil.nextLatitude();
          lons[j] = GeoTestUtil.nextLongitude();
        }
      }
      return new Line(lats, lons);
    }
    return nextLine();
  }

  @Override
  protected Field[] createIndexableFields(String field, Object line) {
    return LatLonShape.createIndexableFields(field, (Line)line);
  }

  @Override
  protected Validator getValidator(QueryRelation queryRelation) {
    VALIDATOR.setRelation(queryRelation);
    return VALIDATOR;
  }

  protected class LineValidator extends Validator {
    @Override
    public boolean testBBoxQuery(double minLat, double maxLat, double minLon, double maxLon, Object shape) {
      Line l = (Line)shape;
      if (queryRelation == QueryRelation.WITHIN) {
        // within: bounding box of shape should be within query box
        double lMinLat = quantizeLat(l.minLat);
        double lMinLon = quantizeLon(l.minLon);
        double lMaxLat = quantizeLat(l.maxLat);
        double lMaxLon = quantizeLon(l.maxLon);

        if (minLon > maxLon) {
          // crosses dateline:
          return minLat <= lMinLat && maxLat >= lMaxLat
              && ((GeoUtils.MIN_LON_INCL <= lMinLon && maxLon >= lMaxLon)
              || (minLon <= lMinLon && GeoUtils.MAX_LON_INCL >= lMaxLon));
        }
        return minLat <= lMinLat && maxLat >= lMaxLat
            && minLon <= lMinLon && maxLon >= lMaxLon;
      }

      Line2D line = Line2D.create(quantizeLine(l));
      Relation r;
      if (minLon > maxLon) {
        // crosses dateline:
        r = line.relate(minLat, maxLat, MIN_LON_INCL, maxLon);
        if (r == Relation.CELL_OUTSIDE_QUERY) {
          r = line.relate(minLat, maxLat, minLon, MAX_LON_INCL);
        }
      } else {
        r = line.relate(minLat, maxLat, minLon, maxLon);
      }

      if (queryRelation == QueryRelation.DISJOINT) {
        return r == Relation.CELL_OUTSIDE_QUERY;
      }
      return r != Relation.CELL_OUTSIDE_QUERY;
    }

    @Override
    public boolean testLineQuery(Line2D line2d, Object shape) {
      return testLine(line2d, (Line) shape);
    }

    @Override
    public boolean testPolygonQuery(Polygon2D poly2d, Object shape) {
      return testLine(poly2d, (Line) shape);
    }

    private boolean testLine(EdgeTree queryPoly, Line line) {
      double ax, ay, bx, by, temp;
      Relation r;
      for (int i = 0, j = 1; j < line.numPoints(); ++i, ++j) {
        ay = quantizeLat(line.getLat(i));
        ax = quantizeLon(line.getLon(i));
        by = quantizeLat(line.getLat(j));
        bx = quantizeLon(line.getLon(j));
        if (ay > by) {
          temp = ay;
          ay = by;
          by = temp;
          temp = ax;
          ax = bx;
          bx = temp;
        } else if (ay == by) {
          if (ax > bx) {
            temp = ay;
            ay = by;
            by = temp;
            temp = ax;
            ax = bx;
            bx = temp;
          }
        }
        r = queryPoly.relateTriangle(ax, ay, bx, by, ax, ay);
        if (queryRelation == QueryRelation.DISJOINT) {
          if (r != Relation.CELL_OUTSIDE_QUERY) return false;
        } else if (queryRelation == QueryRelation.WITHIN) {
          if (r != Relation.CELL_INSIDE_QUERY) return false;
        } else {
          if (r != Relation.CELL_OUTSIDE_QUERY) return true;
        }
      }
      return queryRelation == QueryRelation.INTERSECTS ? false : true;
    }
  }
}
