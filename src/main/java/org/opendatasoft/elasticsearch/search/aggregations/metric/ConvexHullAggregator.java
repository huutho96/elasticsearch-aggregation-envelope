package org.opendatasoft.elasticsearch.search.aggregations.metric;

import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.lease.Releasables;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.ObjectArray;
import org.elasticsearch.index.fielddata.MultiGeoPointValues;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.LeafBucketCollector;
import org.elasticsearch.search.aggregations.LeafBucketCollectorBase;
import org.elasticsearch.search.aggregations.metrics.MetricsAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.internal.SearchContext;
import org.locationtech.jts.algorithm.ConvexHull;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConvexHullAggregator extends MetricsAggregator {

    private final ValuesSource.GeoPoint valuesSource;
    private MultiGeoPointValues values;
    private ObjectArray<Set<Coordinate>> geoPoints;

    protected ConvexHullAggregator(
            String name, SearchContext context, Aggregator parent,
            ValuesSource.GeoPoint valuesSource, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) throws IOException {
        super(name, context, parent, pipelineAggregators, metaData);
        this.valuesSource = valuesSource;
        geoPoints = context.bigArrays().newObjectArray(10);
    }

    @Override
    public LeafBucketCollector getLeafCollector(LeafReaderContext ctx, LeafBucketCollector sub) {
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final BigArrays bigArrays = context.bigArrays();
        final MultiGeoPointValues values = valuesSource.geoPointValues(ctx);
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                if (bucket >= geoPoints.size()) {
                    geoPoints = bigArrays.grow(geoPoints, bucket + 1);
                }

                Set<Coordinate> polygon = geoPoints.get(bucket);

                if (polygon == null) {
                    polygon = new HashSet<Coordinate>();
                    geoPoints.set(bucket, polygon);
                }

                values.advanceExact(doc);
                final int valuesCount = values.docValueCount();

                for (int i=0; i < valuesCount; i++) {
                    GeoPoint value = values.nextValue();
                    polygon.add(new Coordinate(value.getLon(), value.getLat()));
                }
            }
        };
    }

    @Override
    public InternalAggregation buildAggregation(long bucket) throws IOException {
        if (valuesSource == null) {
            return buildEmptyAggregation();
        }
        Set<Coordinate> points = geoPoints.get(bucket);

        if (points == null) {
            return buildEmptyAggregation();
        }

        Geometry convexHull = new ConvexHull(
                points.toArray(new Coordinate[points.size()]),
                ShapeBuilder.FACTORY
        ).getConvexHull();

        return new InternalConvexHull(name, convexHull, pipelineAggregators(), metaData());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalConvexHull(name, null, pipelineAggregators(), metaData());
    }

    @Override
    protected void doClose() {
        Releasables.close(geoPoints);
    }

    public static class Factory extends ValuesSourceAggregatorFactory<ValuesSource.GeoPoint> {

        protected Factory(
                String name, ValuesSourceConfig<ValuesSource.GeoPoint> config,
                QueryShardContext context, AggregatorFactory parent,
                AggregatorFactories.Builder subFactoriesBuilder,
                Map<String, Object> metaData) throws IOException {
            super(name, config, context, parent, subFactoriesBuilder, metaData);
        }

        @Override
        protected Aggregator createUnmapped(
                SearchContext searchContext,
                Aggregator parent, List<PipelineAggregator> list,
                Map<String, Object> metaData) throws IOException {
            return new ConvexHullAggregator(name, searchContext, parent, null, list, metaData);
        }

        @Override
        protected Aggregator doCreateInternal(
                ValuesSource.GeoPoint valuesSource, SearchContext searchContext, Aggregator parent,
                boolean collectsFromSingleBucket, List<PipelineAggregator> list,
                Map<String, Object> metaData) throws IOException {
            return new ConvexHullAggregator(name, searchContext, parent, valuesSource, list, metaData);
        }
    }
}
