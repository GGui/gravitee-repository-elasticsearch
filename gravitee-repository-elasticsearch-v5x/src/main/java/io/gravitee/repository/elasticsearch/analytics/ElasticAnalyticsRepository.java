/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.repository.elasticsearch.analytics;

import io.gravitee.repository.analytics.AnalyticsException;
import io.gravitee.repository.analytics.api.AnalyticsRepository;
import io.gravitee.repository.analytics.query.*;
import io.gravitee.repository.analytics.query.count.CountQuery;
import io.gravitee.repository.analytics.query.count.CountResponse;
import io.gravitee.repository.analytics.query.groupby.GroupByQuery;
import io.gravitee.repository.analytics.query.groupby.GroupByResponse;
import io.gravitee.repository.analytics.query.response.Response;
import io.gravitee.repository.analytics.query.response.histogram.Bucket;
import io.gravitee.repository.analytics.query.response.histogram.Data;
import io.gravitee.repository.analytics.query.response.histogram.DateHistogramResponse;
import io.gravitee.repository.elasticsearch.AbstractElasticRepository;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.ExtendedBounds;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.range.Range;
import org.elasticsearch.search.aggregations.bucket.range.RangeAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.InternalMappedTerms;
import org.elasticsearch.search.aggregations.bucket.terms.InternalTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.metrics.InternalNumericMetricsAggregation;
import org.joda.time.DateTime;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.elasticsearch.search.aggregations.AggregationBuilders.*;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Titouan COMPIEGNE (titouan.compiegne at graviteesource.com)
 * @author GraviteeSource Team
 */
public class ElasticAnalyticsRepository extends AbstractElasticRepository implements AnalyticsRepository {

    @Override
    public <T extends Response> T query(Query<T> query) throws AnalyticsException {
        if (query instanceof DateHistogramQuery) {
            SearchRequestBuilder requestBuilder = prepare((DateHistogramQuery) query);
            return (T) execute(requestBuilder, toDateHistogramResponse((DateHistogramQuery) query));
        } else if (query instanceof GroupByQuery) {
            SearchRequestBuilder requestBuilder = prepare((GroupByQuery) query);
            return (T) execute(requestBuilder, toGroupByResponse());
        } else if (query instanceof CountQuery) {
            SearchRequestBuilder requestBuilder = prepare((CountQuery) query);
            return (T) execute(requestBuilder, toCountResponse());
        }

        return null;
    }

    private SearchRequestBuilder prepare(CountQuery countQuery) throws AnalyticsException {
        return init(countQuery);
    }

    private SearchRequestBuilder prepare(DateHistogramQuery histogramQuery) throws AnalyticsException {
        SearchRequestBuilder requestBuilder = init(histogramQuery);

        // Calculate aggregation
        AggregationBuilder byDateAggregation = dateHistogram("by_date")
                .extendedBounds(new ExtendedBounds(histogramQuery.timeRange().range().from(), histogramQuery.timeRange().range().to()))
                .field(FIELD_TIMESTAMP)
                .interval(histogramQuery.timeRange().interval().toMillis())
                .minDocCount(0);

        // Create hits by aggregation
        if (! histogramQuery.aggregations().isEmpty()) {
            histogramQuery.aggregations().forEach(aggregation -> {
                AbstractAggregationBuilder hitsByAggregation = buildAggregation(aggregation);
                if (hitsByAggregation != null) {
                    byDateAggregation.subAggregation(hitsByAggregation);
                }
            });
        }

        // Set aggregation
        requestBuilder.addAggregation(byDateAggregation);

        return requestBuilder;
    }

    private SearchRequestBuilder prepare(GroupByQuery groupByQuery) throws AnalyticsException {
        SearchRequestBuilder requestBuilder = init(groupByQuery);

        if (! groupByQuery.groups().isEmpty()) {
            RangeAggregationBuilder groupByRangeAggregation = range("by_" + groupByQuery.field() + "_range")
                    .field(groupByQuery.field());

            // Add ranges
            groupByQuery.groups().forEach(range -> groupByRangeAggregation.addRange(range.from(), range.to()));

            // set aggregation
            requestBuilder.addAggregation(groupByRangeAggregation);

        } else {
            // Define aggregation
            TermsAggregationBuilder topHitsAggregation =
                    terms("by_" + groupByQuery.field())
                            .field(groupByQuery.field())
                            .size(20); // Size must be set from the groupByQuery

            // Set the order
            if (groupByQuery.sort() != null) {
                Sort sort = groupByQuery.sort();
                topHitsAggregation.order(
                        Terms.Order.aggregation(
                                sort.getType().name() + '_' + sort.getField(),
                                sort.getOrder() == Order.ASC));
                topHitsAggregation.subAggregation(
                        AggregationBuilders.avg(sort.getType().name() + '_' + sort.getField()).field(sort.getField()));
            }

            // Set aggregation
            requestBuilder.addAggregation(topHitsAggregation);
        }

        return requestBuilder;
    }

    private Function<SearchResponse, DateHistogramResponse> toDateHistogramResponse(DateHistogramQuery query) {
        return response -> {
            DateHistogramResponse dateHistogramResponse = new DateHistogramResponse();

            if (response.getAggregations() == null) {
                return dateHistogramResponse;
            }

            // Prepare data
            Map<String, Bucket> fieldBuckets = new HashMap<>();

            Histogram dateHistogram = (Histogram) response.getAggregations().iterator().next();
            for (Histogram.Bucket dateBucket : dateHistogram.getBuckets()) {
                final long keyAsDate = ((DateTime) dateBucket.getKey()).getMillis();
                dateHistogramResponse.timestamps().add(keyAsDate);

                Iterator<Aggregation> subAggregationsIte = dateBucket.getAggregations().iterator();
                if (subAggregationsIte.hasNext()) {
                    while (subAggregationsIte.hasNext()) {
                        Aggregation subAggregation = subAggregationsIte.next();
                        Bucket fieldBucket = fieldBuckets.get(subAggregation.getName());
                        if (fieldBucket == null) {
                            fieldBucket = new Bucket(subAggregation.getName(), subAggregation.getName().split("_")[1]);
                            fieldBuckets.put(subAggregation.getName(), fieldBucket);
                        }

                        Map<String, List<Data>> bucketData = fieldBucket.data();
                        List<Data> data;

                        if (subAggregation instanceof InternalMappedTerms) {
                            InternalMappedTerms terms = (InternalMappedTerms) subAggregation;
                            for (Terms.Bucket subTermsBucket : (List<Terms.Bucket>) terms.getBuckets()) {
                                data = bucketData.get(subTermsBucket.getKeyAsString());
                                if (data == null) {
                                    data = new ArrayList<>();
                                    bucketData.put(subTermsBucket.getKeyAsString(), data);
                                }
                                data.add(new Data(keyAsDate, subTermsBucket.getDocCount()));
                            }
                        } else if (subAggregation instanceof InternalNumericMetricsAggregation.SingleValue) {
                            InternalNumericMetricsAggregation.SingleValue singleValue = (InternalNumericMetricsAggregation.SingleValue) subAggregation;
                            if (Double.isFinite(singleValue.value())) {
                                data = bucketData.get(singleValue.getName());
                                if (data == null) {
                                    data = new ArrayList<>();
                                    bucketData.put(singleValue.getName(), data);
                                }
                                data.add(new Data(keyAsDate, (long) singleValue.value()));
                            }
                        }
                    }
                }
            }

            if (! query.aggregations().isEmpty()) {
                query.aggregations().forEach(aggregation -> {
                    String key = aggregation.type().name().toLowerCase() + '_' + aggregation.field();
                    if (aggregation.type() == AggregationType.FIELD) {
                        key = "by_" + aggregation.field();
                    }

                    dateHistogramResponse.values().add(fieldBuckets.get(key));
                });
            }

            return dateHistogramResponse;
        };
    }

    private Function<SearchResponse, GroupByResponse> toGroupByResponse() {
        return response -> {
            GroupByResponse groupByresponse = new GroupByResponse();

            if (response.getAggregations() == null) {
                return groupByresponse;
            }

            Aggregation aggregation = response.getAggregations().iterator().next();
            groupByresponse.setField(aggregation.getName().split("_")[1]);

            if (aggregation instanceof Range) {
                Range range = (Range) aggregation;
                for (Range.Bucket bucket : range.getBuckets()) {
                    GroupByResponse.Bucket value =
                            new GroupByResponse.Bucket(bucket.getKeyAsString(), bucket.getDocCount());
                    groupByresponse.values().add(value);
                }
            } else if (aggregation instanceof InternalTerms) {
                InternalTerms terms = (InternalTerms) aggregation;
                terms.getBuckets().forEach(new Consumer<Terms.Bucket>() {
                    @Override
                    public void accept(Terms.Bucket bucket) {
                        List<Aggregation> aggregations = bucket.getAggregations().asList();

                        if (! aggregations.isEmpty())  {
                            Aggregation singleAggregation = aggregations.get(0);
                            if (singleAggregation instanceof InternalNumericMetricsAggregation.SingleValue) {
                                InternalNumericMetricsAggregation.SingleValue singleValue = (InternalNumericMetricsAggregation.SingleValue) singleAggregation;
                                GroupByResponse.Bucket value =
                                        new GroupByResponse.Bucket(bucket.getKeyAsString(), (long) singleValue.value());
                                groupByresponse.values().add(value);
                            }
                        } else {
                            GroupByResponse.Bucket value =
                                    new GroupByResponse.Bucket(bucket.getKeyAsString(), bucket.getDocCount());
                            groupByresponse.values().add(value);
                        }
                    }
                });
            }

            return groupByresponse;
        };
    }

    private Function<SearchResponse, CountResponse> toCountResponse() {
        return response -> {
            CountResponse countResponse = new CountResponse();
            countResponse.setCount(response.getHits().totalHits());

            return countResponse;
        };
    }

    private AbstractAggregationBuilder buildAggregation(io.gravitee.repository.analytics.query.Aggregation aggregation) {
        AbstractAggregationBuilder aggregationBuilder = null;

        if (aggregation != null) {
            switch (aggregation.type()) {
                case FIELD:
                    aggregationBuilder = terms("by_" + aggregation.field()).field(aggregation.field()); //.size(0);
                    break;
                case MIN:
                    aggregationBuilder = min("min_" + aggregation.field()).field(aggregation.field());
                    break;
                case MAX:
                    aggregationBuilder = max("max_" + aggregation.field()).field(aggregation.field());
                    break;
                case AVG:
                    aggregationBuilder = avg("avg_" + aggregation.field()).field(aggregation.field());
                    break;
            }
        }

        return aggregationBuilder;
    }

}
