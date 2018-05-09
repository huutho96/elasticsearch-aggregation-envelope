package org.opendatasoft.elasticsearch.search.aggregations.metric;

import org.apache.logging.log4j.Logger;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import org.apache.lucene.index.LeafReaderContext;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.geo.builders.ShapeBuilder;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.ObjectArray;
import org.elasticsearch.index.fielddata.MultiGeoPointValues;
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

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConvexHullAggregator extends MetricsAggregator {
    private static final Logger logger = ESLoggerFactory.getLogger(ConvexHullAggregator.class);

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
        logger.info("Calling getLeafCollector()");
        if (valuesSource == null) {
            return LeafBucketCollector.NO_OP_COLLECTOR;
        }
        final BigArrays bigArrays = context.bigArrays();
        final MultiGeoPointValues values = valuesSource.geoPointValues(ctx);
        return new LeafBucketCollectorBase(sub, values) {
            @Override
            public void collect(int doc, long bucket) throws IOException {
                logger.info("Calling collect");
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
        logger.info("Calling buildAggregation(" + bucket + ")");
        if (valuesSource == null) {
            logger.info("valuesSource is null");
            return buildEmptyAggregation();
        }
        logger.info("valuesSources: " + valuesSource);
        Set<Coordinate> points = geoPoints.get(bucket);

        if (points == null) {
            logger.info("points is null");
            return buildEmptyAggregation();
        }

        Geometry convexHull = new com.vividsolutions.jts.algorithm.ConvexHull(
                points.toArray(new Coordinate[points.size()]),
                ShapeBuilder.FACTORY
        ).getConvexHull();
        logger.info(convexHull);

        return new InternalConvexHull(name, convexHull, pipelineAggregators(), metaData());
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        logger.info("Calling buildEmptyAggregation()");
        return new InternalConvexHull(name, null, pipelineAggregators(), metaData());
    }

    public static class Factory extends ValuesSourceAggregatorFactory<ValuesSource.GeoPoint, ConvexHullAggregator.Factory> {

        protected Factory(
                String name, ValuesSourceConfig<ValuesSource.GeoPoint> config,
                SearchContext context, AggregatorFactory<?> parent,
                AggregatorFactories.Builder subFactoriesBuilder,
                Map<String, Object> metaData) throws IOException {
            super(name, config, context, parent, subFactoriesBuilder, metaData);
        }

        @Override
        protected Aggregator createUnmapped(
                Aggregator parent, List<PipelineAggregator> list,
                Map<String, Object> metaData) throws IOException {
            return new ConvexHullAggregator(name, context, parent, null, list, metaData);
        }

        @Override
        protected Aggregator doCreateInternal(
                ValuesSource.GeoPoint valuesSource, Aggregator parent,
                boolean collectsFromSingleBucket, List<PipelineAggregator> list,
                Map<String, Object> metaData) throws IOException {
            return new ConvexHullAggregator(name, context, parent, valuesSource, list, metaData);
        }
    }
}