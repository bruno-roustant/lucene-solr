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
package org.apache.solr.handler.component;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.invoke.MethodHandles;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import com.carrotsearch.hppc.IntIntHashMap;
import com.google.common.annotations.VisibleForTesting;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.*;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.FieldComparatorSource;
import org.apache.lucene.search.SimpleFieldComparator;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefBuilder;
import org.apache.lucene.util.SentinelIntSet;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.params.QueryElevationParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.common.util.StrUtils;
import org.apache.solr.core.Config;
import org.apache.solr.core.SolrCore;
import org.apache.solr.response.transform.ElevatedMarkerFactory;
import org.apache.solr.response.transform.ExcludedMarkerFactory;
import org.apache.solr.schema.FieldType;
import org.apache.solr.schema.SchemaField;
import org.apache.solr.search.QueryParsing;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SortSpec;
import org.apache.solr.search.grouping.GroupingSpecification;
import org.apache.solr.util.DOMUtil;
import org.apache.solr.util.RefCounted;
import org.apache.solr.util.VersionedFile;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * A component to elevate some documents to the top of the result set.
 *
 * @since solr 1.3
 */
public class QueryElevationComponent extends SearchComponent implements SolrCoreAware {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  // Constants used in solrconfig.xml
  @VisibleForTesting
  static final String FIELD_TYPE = "queryFieldType";
  @VisibleForTesting
  static final String CONFIG_FILE = "config-file";
  private static final String EXCLUDE = "exclude";
  public static final String BOOSTED = "BOOSTED";
  private static final String BOOSTED_DOCIDS = "BOOSTED_DOCIDS";
  public static final String BOOSTED_PRIORITY = "BOOSTED_PRIORITY";

  public static final String EXCLUDED = "EXCLUDED";

  private static final boolean DEFAULT_FORCE_ELEVATION = false;
  private static final boolean DEFAULT_KEEP_ELEVATION_PRIORITY = true;
  private static final boolean DEFAULT_SUBSET_MATCH = false;
  private static final String DEFAULT_EXCLUDE_MARKER_FIELD_NAME = "excluded";
  private static final String DEFAULT_EDITORIAL_MARKER_FIELD_NAME = "elevated";

  private static final Collector<CharSequence, ?, String> QUERY_EXACT_JOINER = Collectors.joining(" ");

  // Runtime param
  private SolrParams initArgs;
  private Analyzer queryAnalyzer;
  private String uniqueKeyFieldName;
  private FieldType uniqueKeyFieldType;
  /**
   * Provides the indexed value corresponding to a readable value.
   */
  private UnaryOperator<String> indexedValueProvider;
  @VisibleForTesting
  boolean forceElevation;
  private boolean keepElevationPriority;
  private boolean initialized;

  /**
   * For each IndexReader, keep an ElevationProvider when the configuration is loaded from the data directory.
   * The key is null if loaded from the config directory, and is never re-loaded.
   */
  private final Map<IndexReader, ElevationProvider> elevationProviderCache = new WeakHashMap<>();

  /**
   * Keep track of a counter each time a configuration file cannot be loaded.
   * Stop trying to load after {@link #getConfigLoadingExceptionHandler()}.{@link LoadingExceptionHandler#getLoadingMaxAttempts getLoadingMaxAttempts()}.
   */
  private final Map<IndexReader, Integer> configLoadingErrorCounters = new WeakHashMap<>();

  @Override
  public void init(NamedList args) {
    this.initArgs = args.toSolrParams();
  }

  @Override
  public void inform(SolrCore core) {
    initialized = false;
    try {
      parseFieldType(core);
      setUniqueKeyField(core);
      parseExcludedMarkerFieldName(core);
      parseEditorialMarkerFieldName(core);
      parseForceElevation();
      parseKeepElevationPriority();
      loadElevationConfiguration(core);
      initialized = true;
    } catch (InitializationException e) {
      assert !initialized;
      handleInitializationException(e, e.exceptionCause);
    } catch (Exception e) {
      assert !initialized;
      handleInitializationException(e, InitializationExceptionHandler.ExceptionCause.OTHER);
    }
  }

  private void parseFieldType(SolrCore core) throws InitializationException {
    String a = initArgs.get(FIELD_TYPE);
    if (a != null) {
      FieldType ft = core.getLatestSchema().getFieldTypes().get(a);
      if (ft == null) {
        throw new InitializationException("Parameter " + FIELD_TYPE + " defines an unknown field type \"" + a + "\"", InitializationExceptionHandler.ExceptionCause.UNKNOWN_FIELD_TYPE);
      }
      queryAnalyzer = ft.getQueryAnalyzer();
    }
  }

  private void setUniqueKeyField(SolrCore core) throws InitializationException {
    SchemaField sf = core.getLatestSchema().getUniqueKeyField();
    if (sf == null) {
      throw new InitializationException("This component requires the schema to have a uniqueKeyField", InitializationExceptionHandler.ExceptionCause.MISSING_UNIQUE_KEY_FIELD);
    }
    uniqueKeyFieldType = sf.getType();
    uniqueKeyFieldName = sf.getName();
    indexedValueProvider = readableValue -> uniqueKeyFieldType.readableToIndexed(readableValue);
  }

  private void parseExcludedMarkerFieldName(SolrCore core) {
    String markerName = initArgs.get(QueryElevationParams.EXCLUDE_MARKER_FIELD_NAME, DEFAULT_EXCLUDE_MARKER_FIELD_NAME);
    core.addTransformerFactory(markerName, new ExcludedMarkerFactory());
  }

  private void parseEditorialMarkerFieldName(SolrCore core) {
    String markerName = initArgs.get(QueryElevationParams.EDITORIAL_MARKER_FIELD_NAME, DEFAULT_EDITORIAL_MARKER_FIELD_NAME);
    core.addTransformerFactory(markerName, new ElevatedMarkerFactory());
  }

  private void parseForceElevation() {
    forceElevation = initArgs.getBool(QueryElevationParams.FORCE_ELEVATION, getDefaultForceElevation());
  }

  private void parseKeepElevationPriority() {
    keepElevationPriority = initArgs.getBool(QueryElevationParams.KEEP_ELEVATION_PRIORITY, getDefaultKeepElevationPriority());
  }

  /**
   * (Re)Loads elevation configuration.
   * <p>
   * Protected access to be called by extending class.
   * </p>
   *
   * @param core The core holding this component.
   * @return The number of elevation rules parsed.
   */
  @SuppressWarnings("WeakerAccess")
  protected int loadElevationConfiguration(SolrCore core) throws Exception {
    synchronized (elevationProviderCache) {
      elevationProviderCache.clear();
      String configFileName = initArgs.get(CONFIG_FILE);
      if (configFileName == null) {
        // Throw an exception which can be handled by an overriding InitializationExceptionHandler (see handleInitializationException()).
        // The default InitializationExceptionHandler will simply skip this exception.
        throw new InitializationException("Missing component parameter " + CONFIG_FILE + " - it has to define the path to the elevation configuration file", InitializationExceptionHandler.ExceptionCause.NO_CONFIG_FILE_DEFINED);
      }
      boolean configFileExists = false;
      ElevationProvider elevationProvider = NO_OP_ELEVATION_PROVIDER;

      // check if using ZooKeeper
      ZkController zkController = core.getCoreContainer().getZkController();
      if (zkController != null) {
        // TODO : shouldn't have to keep reading the config name when it has been read before
        configFileExists = zkController.configFileExists(zkController.getZkStateReader().readConfigName(core.getCoreDescriptor().getCloudDescriptor().getCollectionName()), configFileName);
      } else {
        File fC = new File(core.getResourceLoader().getConfigDir(), configFileName);
        File fD = new File(core.getDataDir(), configFileName);
        if (fC.exists() == fD.exists()) {
          InitializationException e = new InitializationException("Missing config file \"" + configFileName + "\" - either " + fC.getAbsolutePath() + " or " + fD.getAbsolutePath() + " must exist, but not both", InitializationExceptionHandler.ExceptionCause.MISSING_CONFIG_FILE);
          elevationProvider = handleConfigLoadingException(e, true);
          elevationProviderCache.put(null, elevationProvider);
        } else if (fC.exists()) {
          if (fC.length() == 0) {
            InitializationException e = new InitializationException("Empty config file \"" + configFileName + "\" - " + fC.getAbsolutePath(), InitializationExceptionHandler.ExceptionCause.EMPTY_CONFIG_FILE);
            elevationProvider = handleConfigLoadingException(e, true);
          } else {
            configFileExists = true;
            log.info("Loading QueryElevation from: " + fC.getAbsolutePath());
            Config cfg = new Config(core.getResourceLoader(), configFileName);
            elevationProvider = loadElevationProvider(cfg);
          }
          elevationProviderCache.put(null, elevationProvider);
        }
      }
      //in other words, we think this is in the data dir, not the conf dir
      if (!configFileExists) {
        // preload the first data
        RefCounted<SolrIndexSearcher> searchHolder = null;
        try {
          searchHolder = core.getNewestSearcher(false);
          if (searchHolder == null) {
            elevationProvider = NO_OP_ELEVATION_PROVIDER;
          } else {
            IndexReader reader = searchHolder.get().getIndexReader();
            elevationProvider = getElevationProvider(reader, core);
          }
        } finally {
          if (searchHolder != null) searchHolder.decref();
        }
      }
      return elevationProvider.size();
    }
  }

  /**
   * Gets the {@link ElevationProvider} from the data dir or from the cache.
   *
   * @return The cached or loaded {@link ElevationProvider}.
   * @throws java.io.IOException                  If the configuration resource cannot be found, or if an I/O error occurs while analyzing the triggering queries.
   * @throws org.xml.sax.SAXException                 If the configuration resource is not a valid XML content.
   * @throws javax.xml.parsers.ParserConfigurationException If the configuration resource is not a valid XML configuration.
   * @throws RuntimeException             If the configuration resource is not an XML content of the expected format
   *                                      (either {@link RuntimeException} or {@link org.apache.solr.common.SolrException}).
   */
  @VisibleForTesting
  ElevationProvider getElevationProvider(IndexReader reader, SolrCore core) throws Exception {
    synchronized (elevationProviderCache) {
      ElevationProvider elevationProvider;
      elevationProvider = elevationProviderCache.get(null);
      if (elevationProvider != null) return elevationProvider;

      elevationProvider = elevationProviderCache.get(reader);
      if (elevationProvider == null) {
        Exception loadingException = null;
        boolean resourceAccessIssue = false;
        try {
          elevationProvider = loadElevationProvider(core);
        } catch (IOException e) {
          loadingException = e;
          resourceAccessIssue = true;
        } catch (Exception e) {
          loadingException = e;
        }
        boolean shouldCache = true;
        if (loadingException != null) {
          elevationProvider = handleConfigLoadingException(loadingException, resourceAccessIssue);
          // Do not cache the fallback ElevationProvider for the first exceptions because the exception might
          // occur only a couple of times and the config file could be loaded correctly afterwards
          // (e.g. temporary invalid file access). After some attempts, cache the fallback ElevationProvider
          // not to overload the exception handler (and beyond it, the logs probably).
          if (incConfigLoadingErrorCount(reader) < getConfigLoadingExceptionHandler().getLoadingMaxAttempts()) {
            shouldCache = false;
          }
        }
        if (shouldCache) {
          elevationProviderCache.put(reader, elevationProvider);
        }
      }
      assert elevationProvider != null;
      return elevationProvider;
    }
  }

  /**
   * Loads the {@link ElevationProvider} from the data dir.
   *
   * @return The loaded {@link ElevationProvider}.
   * @throws java.io.IOException                  If the configuration resource cannot be found, or if an I/O error occurs while analyzing the triggering queries.
   * @throws org.xml.sax.SAXException                 If the configuration resource is not a valid XML content.
   * @throws javax.xml.parsers.ParserConfigurationException If the configuration resource is not a valid XML configuration.
   * @throws RuntimeException             If the configuration resource is not an XML content of the expected format
   *                                      (either {@link RuntimeException} or {@link org.apache.solr.common.SolrException}).
   */
  private ElevationProvider loadElevationProvider(SolrCore core) throws IOException, SAXException, ParserConfigurationException {
    String configFileName = initArgs.get(CONFIG_FILE);
    if (configFileName == null) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "QueryElevationComponent must specify argument: " + CONFIG_FILE);
    }
    log.info("Loading QueryElevation from data dir: " + configFileName);

    Config cfg;
    ZkController zkController = core.getCoreContainer().getZkController();
    if (zkController != null) {
      cfg = new Config(core.getResourceLoader(), configFileName, null, null);
    } else {
      InputStream is = VersionedFile.getLatestFile(core.getDataDir(), configFileName);
      cfg = new Config(core.getResourceLoader(), configFileName, new InputSource(is), null);
    }
    ElevationProvider elevationProvider = loadElevationProvider(cfg);
    assert elevationProvider != null;
    return elevationProvider;
  }

  /**
   * Loads the {@link ElevationProvider}.
   *
   * @throws java.io.IOException      If an I/O error occurs while analyzing the triggering queries.
   * @throws RuntimeException If the config does not provide an XML content of the expected format
   *                          (either {@link RuntimeException} or {@link org.apache.solr.common.SolrException}).
   */
  @SuppressWarnings("WeakerAccess")
  protected ElevationProvider loadElevationProvider(Config config) throws IOException {
    Map<ElevatingQuery, ElevationBuilder> elevationBuilderMap = keepElevationPriority ?
            new LinkedHashMap<>() : new HashMap<>();
    XPath xpath = XPathFactory.newInstance().newXPath();
    NodeList nodes = (NodeList) config.evaluate("elevate/query", XPathConstants.NODESET);
    for (int i = 0; i < nodes.getLength(); i++) {
      Node node = nodes.item(i);
      String queryString = DOMUtil.getAttr(node, "text", "missing query 'text'");
      String matchString = DOMUtil.getAttr(node, "match");
      ElevatingQuery elevatingQuery = new ElevatingQuery(queryString, parseMatchPolicy(matchString));

      NodeList children;
      try {
        children = (NodeList) xpath.evaluate("doc", node, XPathConstants.NODESET);
      } catch (XPathExpressionException e) {
        throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
            "query requires '<doc .../>' child");
      }

      ElevationBuilder elevationBuilder = new ElevationBuilder();
      for (int j = 0; j < children.getLength(); j++) {
        Node child = children.item(j);
        String id = DOMUtil.getAttr(child, "id", "missing 'id'");
        String e = DOMUtil.getAttr(child, EXCLUDE, null);
        if (e != null) {
          if (Boolean.valueOf(e)) {
            elevationBuilder.addExcludedId(id);
            continue;
          }
        }
        elevationBuilder.addElevatedId(id);
      }

      // It is allowed to define multiple times different elevations for the same query. In this case the elevations
      // are merged in the ElevationBuilder (they will be triggered at the same time).
      ElevationBuilder previousElevationBuilder = elevationBuilderMap.get(elevatingQuery);
      if (previousElevationBuilder == null) {
        elevationBuilderMap.put(elevatingQuery, elevationBuilder);
      } else {
        previousElevationBuilder.merge(elevationBuilder);
      }
    }
    return createElevationProvider(queryAnalyzer, elevationBuilderMap);
  }

  private boolean parseMatchPolicy(String matchString) {
    if (matchString == null) {
      return getDefaultSubsetMatch();
    } else if (matchString.equalsIgnoreCase("exact")) {
      return false;
    } else if (matchString.equalsIgnoreCase("subset")) {
      return true;
    } else {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR,
          "invalid value \"" + matchString + "\" for query match attribute");
    }
  }

  /**
   * Potentially handles and captures an exception that occurred while loading the configuration resource.
   *
   * @param e                   The exception caught.
   * @param resourceAccessIssueOrEmptyConfig <code>true</code> if the exception has been thrown because the resource could not
   *                            be accessed (missing or cannot be read) or the config file is empty; <code>false</code> if the resource has
   *                            been found and accessed but the error occurred while loading the resource
   *                            (invalid format, incomplete or corrupted).
   * @return The {@link ElevationProvider} to use if the exception is absorbed.
   * @throws E If the exception is not absorbed.
   */
  private <E extends Exception> ElevationProvider handleConfigLoadingException(E e, boolean resourceAccessIssueOrEmptyConfig) throws E {
    if (getConfigLoadingExceptionHandler().handleLoadingException(e, resourceAccessIssueOrEmptyConfig)) {
      return NO_OP_ELEVATION_PROVIDER;
    }
    assert e != null;
    throw e;
  }

  private int incConfigLoadingErrorCount(IndexReader reader) {
    Integer counter = configLoadingErrorCounters.get(reader);
    if (counter == null) {
      counter = 1;
    } else {
      counter++;
    }
    configLoadingErrorCounters.put(reader, counter);
    return counter;
  }

  /**
   * Potentially handles and captures the exception that occurred while initializing this component. If the exception
   * is captured by the handler, this component fails to initialize silently and is muted because field initialized is
   * false.
   */
  private void handleInitializationException(Exception initializationException, InitializationExceptionHandler.ExceptionCause exceptionCause) {
    SolrException solrException = new SolrException(SolrException.ErrorCode.SERVER_ERROR,
        "Error initializing " + QueryElevationComponent.class.getSimpleName(), initializationException);
    if (!getInitializationExceptionHandler().handleInitializationException(solrException, exceptionCause))
      throw solrException;
  }

  //---------------------------------------------------------------------------------
  // SearchComponent
  //---------------------------------------------------------------------------------

  @Override
  public void prepare(ResponseBuilder rb) throws IOException {
    if (!initialized || !rb.req.getParams().getBool(QueryElevationParams.ENABLE, true)) {
      return;
    }

    Elevation elevation = getElevation(rb);
    if (elevation != null) {
      setQuery(rb, elevation);
      setSort(rb, elevation);
    }

    if (rb.isDebug() && rb.isDebugQuery()) {
      addDebugInfo(rb, elevation);
    }
  }

  @Override
  public void process(ResponseBuilder rb) throws IOException {
    // Do nothing -- the real work is modifying the input query
  }

  private Elevation getElevation(ResponseBuilder rb) {
    SolrParams localParams = rb.getQparser().getLocalParams();
    String queryString = localParams == null ? rb.getQueryString() : localParams.get(QueryParsing.V);
    if (queryString == null || rb.getQuery() == null) {
      return null;
    }

    SolrParams params = rb.req.getParams();
    String paramElevatedIds = params.get(QueryElevationParams.IDS);
    String paramExcludedIds = params.get(QueryElevationParams.EXCLUDE);
    try {
      if (paramElevatedIds != null || paramExcludedIds != null) {
        List<String> elevatedIds = paramElevatedIds != null ? StrUtils.splitSmart(paramElevatedIds,",", true) : Collections.emptyList();
        List<String> excludedIds = paramExcludedIds != null ? StrUtils.splitSmart(paramExcludedIds, ",", true) : Collections.emptyList();
        return new ElevationBuilder().addElevatedIds(elevatedIds).addExcludedIds(excludedIds).build();
      } else {
        IndexReader reader = rb.req.getSearcher().getIndexReader();
        return getElevationProvider(reader, rb.req.getCore()).getElevationForQuery(queryString);
      }
    } catch (Exception e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Error loading elevation", e);
    }
  }

  private void setQuery(ResponseBuilder rb, Elevation elevation) {
    rb.req.getContext().put(BOOSTED, elevation.elevatedIds);
    rb.req.getContext().put(BOOSTED_PRIORITY, elevation.priorities);

    // Change the query to insert forced documents
    SolrParams params = rb.req.getParams();
    if (params.getBool(QueryElevationParams.EXCLUSIVE, false)) {
      // We only want these elevated results
      rb.setQuery(new BoostQuery(elevation.includeQuery, 0f));
    } else {
      BooleanQuery.Builder queryBuilder = new BooleanQuery.Builder();
      queryBuilder.add(rb.getQuery(), BooleanClause.Occur.SHOULD);
      queryBuilder.add(new BoostQuery(elevation.includeQuery, 0f), BooleanClause.Occur.SHOULD);
      if (elevation.excludeQueries != null) {
        if (params.getBool(QueryElevationParams.MARK_EXCLUDES, false)) {
          // We are only going to mark items as excluded, not actually exclude them.
          // This works with the EditorialMarkerFactory.
          rb.req.getContext().put(EXCLUDED, elevation.excludedIds);
        } else {
          for (TermQuery tq : elevation.excludeQueries) {
            queryBuilder.add(new BooleanClause(tq, BooleanClause.Occur.MUST_NOT));
          }
        }
      }
      rb.setQuery(queryBuilder.build());
    }
  }

  private void setSort(ResponseBuilder rb, Elevation elevation) {
    boolean forceElevation = rb.req.getParams().getBool(QueryElevationParams.FORCE_ELEVATION, this.forceElevation);
    ElevationComparatorSource comparator = new ElevationComparatorSource(elevation);
    setSortSpec(rb, forceElevation, comparator);
    setGroupingSpec(rb, forceElevation, comparator);
  }

  private void setSortSpec(ResponseBuilder rb, boolean forceElevation, ElevationComparatorSource comparator) {
    // if the sort is 'score desc' use a custom sorting method to
    // insert documents in their proper place
    SortSpec sortSpec = rb.getSortSpec();
    if (sortSpec.getSort() == null) {
      sortSpec.setSortAndFields(
              new Sort(
                      new SortField("_elevate_", comparator, true),
                      new SortField(null, SortField.Type.SCORE, false)),
              Arrays.asList(new SchemaField[2]));
    } else {
      // Check if the sort is based on score
      SortSpec modSortSpec = this.modifySortSpec(sortSpec, forceElevation, comparator);
      if (null != modSortSpec) {
        rb.setSortSpec(modSortSpec);
      }
    }
  }

  private void setGroupingSpec(ResponseBuilder rb, boolean forceElevation, ElevationComparatorSource comparator) {
    // alter the sorting in the grouping specification if there is one
    GroupingSpecification groupingSpec = rb.getGroupingSpec();
    if(groupingSpec != null) {
      SortSpec groupSortSpec = groupingSpec.getGroupSortSpec();
      SortSpec modGroupSortSpec = this.modifySortSpec(groupSortSpec, forceElevation, comparator);
      if (modGroupSortSpec != null) {
        groupingSpec.setGroupSortSpec(modGroupSortSpec);
      }
      SortSpec withinGroupSortSpec = groupingSpec.getWithinGroupSortSpec();
      SortSpec modWithinGroupSortSpec = this.modifySortSpec(withinGroupSortSpec, forceElevation, comparator);
      if (modWithinGroupSortSpec != null) {
        groupingSpec.setWithinGroupSortSpec(modWithinGroupSortSpec);
      }
    }
  }

  private SortSpec modifySortSpec(SortSpec current, boolean forceElevation, ElevationComparatorSource comparator) {
    boolean modify = false;
    SortField[] currentSorts = current.getSort().getSort();
    List<SchemaField> currentFields = current.getSchemaFields();

    ArrayList<SortField> sorts = new ArrayList<>(currentSorts.length + 1);
    List<SchemaField> fields = new ArrayList<>(currentFields.size() + 1);

    // Perhaps force it to always sort by score
    if (forceElevation && currentSorts[0].getType() != SortField.Type.SCORE) {
      sorts.add(new SortField("_elevate_", comparator, true));
      fields.add(null);
      modify = true;
    }
    for (int i = 0; i < currentSorts.length; i++) {
      SortField sf = currentSorts[i];
      if (sf.getType() == SortField.Type.SCORE) {
        sorts.add(new SortField("_elevate_", comparator, !sf.getReverse()));
        fields.add(null);
        modify = true;
      }
      sorts.add(sf);
      fields.add(currentFields.get(i));
    }
    return modify ?
            new SortSpec(new Sort(sorts.toArray(new SortField[sorts.size()])),
                    fields,
                    current.getCount(),
                    current.getOffset())
            : null;
  }

  private void addDebugInfo(ResponseBuilder rb, Elevation elevation) {
    List<String> match = null;
    if (elevation != null) {
      // Extract the elevated terms into a list
      match = new ArrayList<>(elevation.includeQuery.clauses().size());
      for (BooleanClause clause : elevation.includeQuery.clauses()) {
        TermQuery tq = (TermQuery) clause.getQuery();
        match.add(tq.getTerm().text());
      }
    }
    SimpleOrderedMap<Object> dbg = new SimpleOrderedMap<>();
    dbg.add("q", rb.getQueryString());
    dbg.add("match", match);
    rb.addDebugInfo("queryBoosting", dbg);
  }

  //---------------------------------------------------------------------------------
  // Boosted docs helper
  //---------------------------------------------------------------------------------

  public static IntIntHashMap getBoostDocs(SolrIndexSearcher indexSearcher, Map<BytesRef, Integer> boosted, Map context) throws IOException {

    IntIntHashMap boostDocs = null;

    if (boosted != null) {

      //First see if it's already in the request context. Could have been put there
      //by another caller.
      if (context != null) {
        boostDocs = (IntIntHashMap) context.get(BOOSTED_DOCIDS);
      }

      if (boostDocs != null) {
        return boostDocs;
      }
      //Not in the context yet so load it.

      SchemaField idField = indexSearcher.getSchema().getUniqueKeyField();
      String fieldName = idField.getName();

      boostDocs = new IntIntHashMap(boosted.size());

      List<LeafReaderContext>leaves = indexSearcher.getTopReaderContext().leaves();
      PostingsEnum postingsEnum = null;
      for (LeafReaderContext leaf : leaves) {
        LeafReader reader = leaf.reader();
        int docBase = leaf.docBase;
        Bits liveDocs = reader.getLiveDocs();
        Terms terms = reader.terms(fieldName);
        TermsEnum termsEnum = terms.iterator();
        Iterator<BytesRef> it = boosted.keySet().iterator();
        while (it.hasNext()) {
          BytesRef ref = it.next();
          if (termsEnum.seekExact(ref)) {
            postingsEnum = termsEnum.postings(postingsEnum);
            int doc = postingsEnum.nextDoc();
            while (doc != PostingsEnum.NO_MORE_DOCS && liveDocs != null && !liveDocs.get(doc)) {
              doc = postingsEnum.nextDoc();
            }
            if (doc != PostingsEnum.NO_MORE_DOCS) {
              //Found the document.
              int p = boosted.get(ref);
              boostDocs.put(doc+docBase, p);
              it.remove();
            }
          }
        }
      }
    }

    if(context != null) {
      //noinspection unchecked
      context.put(BOOSTED_DOCIDS, boostDocs);
    }

    return boostDocs;
  }

  //---------------------------------------------------------------------------------
  // SolrInfoBean
  //---------------------------------------------------------------------------------

  @Override
  public String getDescription() {
    return "Query Boosting -- boost particular documents for a given query";
  }

  //---------------------------------------------------------------------------------
  // Overrides
  //---------------------------------------------------------------------------------

  /**
   * Gets the default value for {@link org.apache.solr.common.params.QueryElevationParams#FORCE_ELEVATION} parameter.
   */
  @SuppressWarnings("WeakerAccess")
  protected boolean getDefaultForceElevation() {
    return DEFAULT_FORCE_ELEVATION;
  }

  /**
   * Gets the default value for {@link #DEFAULT_KEEP_ELEVATION_PRIORITY} parameter.
   */
  @SuppressWarnings("WeakerAccess")
  protected boolean getDefaultKeepElevationPriority() {
    return DEFAULT_KEEP_ELEVATION_PRIORITY;
  }

  /**
   * Gets the default subset match policy.
   */
  @SuppressWarnings("WeakerAccess")
  protected boolean getDefaultSubsetMatch() {
    return DEFAULT_SUBSET_MATCH;
  }

  /**
   * Gets the {@link InitializationExceptionHandler} that handles exception thrown during the initialization of the
   * elevation configuration.
   */
  @SuppressWarnings("WeakerAccess")
  protected InitializationExceptionHandler getInitializationExceptionHandler() {
    return InitializationExceptionHandler.NO_OP;
  }

  /**
   * Gets the {@link LoadingExceptionHandler} that handles exception thrown during the loading of the elevation configuration.
   */
  @SuppressWarnings("WeakerAccess")
  protected LoadingExceptionHandler getConfigLoadingExceptionHandler() {
    return LoadingExceptionHandler.NO_OP;
  }

  /**
   * Creates the {@link ElevationProvider} to set during configuration loading. The same instance will be used later
   * when elevating results for queries.
   *
   * @param queryAnalyzer to analyze and tokenize the query.
   * @param elevationBuilderMap map of all {@link ElevatingQuery} and their corresponding {@link ElevationBuilder}.
   * @return The created {@link ElevationProvider}.
   */
  @SuppressWarnings("WeakerAccess")
  protected ElevationProvider createElevationProvider(Analyzer queryAnalyzer, Map<ElevatingQuery, ElevationBuilder> elevationBuilderMap) {
    return new MapElevationProvider(queryAnalyzer, elevationBuilderMap);
  }

  //---------------------------------------------------------------------------------
  // Query analysis and tokenization
  //---------------------------------------------------------------------------------

  @VisibleForTesting
  String analyzeQuery(String queryString) {
    return analyzeQuery(queryString, queryAnalyzer);
  }

  /**
   * Analyzes the provided query string and returns a concatenation of the analyzed tokens.
   */
  private static String analyzeQuery(String queryString, Analyzer queryAnalyzer) {
    if (queryAnalyzer == null) {
      return queryString;
    }
    Collection<String> queryTerms = new ArrayList<>();
    splitQueryTermsWithAnalyzer(queryString, queryAnalyzer, queryTerms);
    return queryTerms.stream().collect(QUERY_EXACT_JOINER);
  }

  private static void splitQueryTermsWithAnalyzer(String queryString, Analyzer queryAnalyzer, Collection<String> tokenCollector) {
    try {
      TokenStream tokens = queryAnalyzer.tokenStream("", new StringReader(queryString));
      tokens.reset();
      CharTermAttribute termAttribute = tokens.addAttribute(CharTermAttribute.class);
      while (tokens.incrementToken()) {
        tokenCollector.add(new String(termAttribute.buffer(), 0, termAttribute.length()));
      }
      tokens.end();
      tokens.close();
    } catch (IOException e) {
      // Will never be thrown since we read a StringReader.
      throw Throwables.propagate(e);
    }
  }

  //---------------------------------------------------------------------------------
  // Testing
  //---------------------------------------------------------------------------------

  /**
   * Helpful for testing without loading config.xml.
   *
   *
   * @param reader      The {@link org.apache.lucene.index.IndexReader}.
   * @param queryString The query for which to elevate some documents. If the query has already been defined an
   *                    elevation, this method overwrites it.
   * @param subsetMatch <code>true</code> for query subset match; <code>false</code> for query exact match.
   * @param elevatedIds The readable ids of the documents to set as top results for the provided query.
   * @param excludedIds The readable ids of the document to exclude from results for the provided query.
   * @throws java.io.IOException If there is a low-level I/O error.
   */
  @VisibleForTesting
  void setTopQueryResults(IndexReader reader, String queryString, boolean subsetMatch, String[] elevatedIds,
                          String[] excludedIds) throws IOException {
    clearElevationProviderCache();
    if (elevatedIds == null) {
      elevatedIds = new String[0];
    }
    if (excludedIds == null) {
      excludedIds = new String[0];
    }
    ElevatingQuery elevatingQuery = new ElevatingQuery(queryString, subsetMatch);
    ElevationBuilder elevationBuilder = new ElevationBuilder()
        .addElevatedIds(Arrays.asList(elevatedIds))
        .addExcludedIds(Arrays.asList(excludedIds));
    Map<ElevatingQuery, ElevationBuilder> elevationBuilderMap = ImmutableMap.of(elevatingQuery, elevationBuilder);
    synchronized (elevationProviderCache) {
      elevationProviderCache.computeIfAbsent(reader, k -> createElevationProvider(queryAnalyzer, elevationBuilderMap));
    }
  }

  @VisibleForTesting
  void clearElevationProviderCache() {
    synchronized (elevationProviderCache) {
        elevationProviderCache.clear();
    }
  }

  //---------------------------------------------------------------------------------
  // Exception classes
  //---------------------------------------------------------------------------------

  private static class InitializationException extends Exception {
    final InitializationExceptionHandler.ExceptionCause exceptionCause;

    InitializationException(String message, InitializationExceptionHandler.ExceptionCause exceptionCause) {
      super(message);
      this.exceptionCause = exceptionCause;
    }
  }

  /**
   * Handles resource loading exception.
   */
  protected interface InitializationExceptionHandler {

    /**
     * NoOp {@link LoadingExceptionHandler} that does not capture any exception and simply returns <code>false</code>.
     */
    InitializationExceptionHandler NO_OP = new InitializationExceptionHandler() {
      @Override
      public boolean handleInitializationException(Exception e, ExceptionCause exceptionCause) {
        return exceptionCause == ExceptionCause.NO_CONFIG_FILE_DEFINED;
      }
    };

    enum ExceptionCause {
      /**
       * The component parameter {@link #FIELD_TYPE} defines an unknown field type.
       */
      UNKNOWN_FIELD_TYPE,
      /**
       * This component requires the schema to have a uniqueKeyField, which it does not have.
       */
      MISSING_UNIQUE_KEY_FIELD,
      /**
       * Missing component parameter {@link #CONFIG_FILE} - it has to define the path to the elevation configuration file (e.g. elevate.xml).
       */
      NO_CONFIG_FILE_DEFINED,
      /**
       * The elevation configuration file (e.g. elevate.xml) cannot be found, or is defined in both conf/ and data/ directories.
       */
      MISSING_CONFIG_FILE,
      /**
       * The elevation configuration file (e.g. elevate.xml) is empty.
       */
      EMPTY_CONFIG_FILE,
      /**
       * Unclassified exception cause.
       */
      OTHER,
    }

    /**
     * Potentially handles and captures an exception that occurred while initializing the component.
     * If the exception is captured, the component fails to initialize silently and is muted.
     *
     * @param e              The exception caught.
     * @param exceptionCause The exception cause.
     * @param <E>            The exception type.
     * @return <code>true</code> if the exception is handled and captured by this handler (and thus will not be
     *         thrown anymore); <code>false</code> if the exception is not captured, in this case it will be probably
     *         thrown again by the calling code.
     * @throws E If this handler throws the exception itself (it may add some cause or message).
     */
    <E extends Exception> boolean handleInitializationException(E e, ExceptionCause exceptionCause) throws E;
  }

  /**
   * Handles resource loading exception.
   */
  protected interface LoadingExceptionHandler {

    /**
     * NoOp {@link LoadingExceptionHandler} that does not capture any exception and simply returns <code>false</code>.
     */
    LoadingExceptionHandler NO_OP = new LoadingExceptionHandler() {
      @Override
      public boolean handleLoadingException(Exception e, boolean resourceAccessIssue) {
        return false;
      }

      @Override
      public int getLoadingMaxAttempts() {
        return 0;
      }
    };

    /**
     * Potentially handles and captures an exception that occurred while loading a resource.
     *
     * @param e                   The exception caught.
     * @param resourceAccessIssue <code>true</code> if the exception has been thrown because the resource could not
     *                            be accessed (missing or cannot be read); <code>false</code> if the resource has
     *                            been found and accessed but the error occurred while loading the resource
     *                            (invalid format, incomplete or corrupted).
     * @param <E>                 The exception type.
     * @return <code>true</code> if the exception is handled and captured by this handler (and thus will not be
     *         thrown anymore); <code>false</code> if the exception is not captured, in this case it will be probably
     *         thrown again by the calling code.
     * @throws E If this handler throws the exception itself (it may add some cause or message).
     */
    <E extends Exception> boolean handleLoadingException(E e, boolean resourceAccessIssue) throws E;

    /**
     * Gets the maximum number of attempts to load the resource in case of error (resource not found, I/O error,
     * invalid format), for each Solr core.
     * After this number of attempts (so {@link #handleLoadingException} is called this number of times),
     * {@link #handleLoadingException} will not be called anymore for the specific Solr core, and the resource is
     * considered empty afterwards (until the core is reloaded).
     *
     * @return The maximum number of attempts to load the resource. The value must be &gt;= 0.
     */
    int getLoadingMaxAttempts();
  }

  //---------------------------------------------------------------------------------
  // Elevation classes
  //---------------------------------------------------------------------------------

  /**
   * Creates an elevation.
   *
   * @param elevatedIds The ids of the elevated documents that should appear on top of search results; can be <code>null</code>.
   * @param excludedIds The ids of the excluded documents that should not appear in search results; can be <code>null</code>.
   */
  private Elevation createElevation(Collection<String> elevatedIds, Collection<String> excludedIds) {
    return new Elevation(elevatedIds, excludedIds, indexedValueProvider, uniqueKeyFieldName, keepElevationPriority);
  }

  /**
   * Provides the elevations defined for queries.
   */
  protected interface ElevationProvider {
    /**
     * Gets the elevation associated to the provided query.
     * <p>
     * By contract and by design, only one elevation may be associated
     * to a given query (this can be safely verified by an assertion).
     *
     * @param queryString The query string (not {@link #analyzeQuery(String, Analyzer) analyzed} yet,
     *              this {@link ElevationProvider} is in charge of analyzing it).
     * @return The elevation associated with the query; or <code>null</code> if none.
     */
    Elevation getElevationForQuery(String queryString);

    /**
     * Gets the number of query elevations in this {@link ElevationProvider}.
     */
    @VisibleForTesting
    int size();
  }

  /**
   * {@link ElevationProvider} that returns no elevation.
   */
  @SuppressWarnings("WeakerAccess")
  protected static final ElevationProvider NO_OP_ELEVATION_PROVIDER = new ElevationProvider() {
    @Override
    public Elevation getElevationForQuery(String queryString) {
      return null;
    }

    @Override
    public int size() {
      return 0;
    }
  };

  /**
   * Simple query exact match {@link ElevationProvider}.
   * <p>
   * It does not support subset matching (see {@link #parseMatchPolicy(String)}).
   * <p>
   * Immutable.
   */
  protected static class MapElevationProvider implements ElevationProvider {

    private final Analyzer queryAnalyzer;
    private final Map<String, Elevation> elevationMap;

    @SuppressWarnings("WeakerAccess")
    public MapElevationProvider(Analyzer queryAnalyzer, Map<ElevatingQuery, ElevationBuilder> elevationBuilderMap) {
      this.queryAnalyzer = queryAnalyzer;
      elevationMap = buildElevationMap(elevationBuilderMap);
    }

    private Map<String, Elevation> buildElevationMap(Map<ElevatingQuery, ElevationBuilder> elevationBuilderMap) {
      Map<String, Elevation> elevationMap = Maps.newHashMapWithExpectedSize(elevationBuilderMap.size());
      for (Map.Entry<ElevatingQuery, ElevationBuilder> entry : elevationBuilderMap.entrySet()) {
        ElevatingQuery elevatingQuery = entry.getKey();
        if (elevatingQuery.subsetMatch) {
          throw new UnsupportedOperationException("Subset matching is not supported by " + getClass().getName());
        }
        String analyzedQuery = analyzeQuery(elevatingQuery.queryString, queryAnalyzer);
        Elevation elevation = entry.getValue().build();
        Elevation duplicateElevation = elevationMap.put(analyzedQuery, elevation);
        if (duplicateElevation != null) {
          throw new IllegalArgumentException("Duplicate elevation for query \"" + analyzedQuery + "\"");
        }
      }
      return Collections.unmodifiableMap(elevationMap);
    }

    @Override
    public Elevation getElevationForQuery(String queryString) {
      String analyzedQuery = analyzeQuery(queryString, queryAnalyzer);
      return elevationMap.get(analyzedQuery);
    }

    @Override
    public int size() {
      return elevationMap.size();
    }
  }

  /**
   * Query triggering elevation.
   */
  protected static class ElevatingQuery {

    @SuppressWarnings("WeakerAccess")
    public final String queryString;
    @SuppressWarnings("WeakerAccess")
    public final boolean subsetMatch;

    /**
     * @param queryString The query to elevate documents for (not the analyzed form).
     * @param subsetMatch Whether to match a subset of query terms.
     */
    @SuppressWarnings("WeakerAccess")
    protected ElevatingQuery(String queryString, boolean subsetMatch) throws IOException {
      this.queryString = queryString;
      this.subsetMatch = subsetMatch;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ElevatingQuery)) {
        return false;
      }
      ElevatingQuery eq = (ElevatingQuery) o;
      return queryString.equals(eq.queryString) && subsetMatch == eq.subsetMatch;
    }

    @Override
    public int hashCode() {
      return queryString.hashCode() + (subsetMatch ? 1 : 0);
    }
  }

  /**
   * Builds an {@link Elevation}. This class is used to start defining query elevations, but allowing the merge of
   * multiple elevations for the same query.
   */
  private class ElevationBuilder {

    /**
     * The ids of the elevated documents that should appear on top of search results; can be <code>null</code>.
     */
    private Set<String> elevatedIds;
    /**
     * The ids of the excluded documents that should not appear in search results; can be <code>null</code>.
     */
    private Set<String> excludedIds;

    ElevationBuilder addElevatedId(String id) {
      if (elevatedIds == null) {
        elevatedIds = createIdSet();
      }
      elevatedIds.add(id);
      return this;
    }

    ElevationBuilder addElevatedIds(List<String> ids) {
      for (String id : ids) {
        addElevatedId(id);
      }
      return this;
    }

    ElevationBuilder addExcludedId(String id) {
      if (excludedIds == null) {
        excludedIds = createIdSet();
      }
      excludedIds.add(id);
      return this;
    }

    ElevationBuilder addExcludedIds(List<String> ids) {
      for (String id : ids) {
        addExcludedId(id);
      }
      return this;
    }

    ElevationBuilder merge(ElevationBuilder elevationBuilder) {
      if (elevatedIds == null) {
        elevatedIds = elevationBuilder.elevatedIds;
      } else if (elevationBuilder.elevatedIds != null) {
        elevatedIds.addAll(elevationBuilder.elevatedIds);
      }
      if (excludedIds == null) {
        excludedIds = elevationBuilder.excludedIds;
      } else if (elevationBuilder.excludedIds != null) {
        excludedIds.addAll(elevationBuilder.excludedIds);
      }
      return this;
    }

    Elevation build() {
      return createElevation(elevatedIds, excludedIds);
    }

    private Set<String> createIdSet() {
      return (keepElevationPriority ? new LinkedHashSet<>() : new HashSet<>());
    }
  }

  /**
   * Elevation of some documents in search results, with potential exclusion of others.
   */
  protected static class Elevation {

    private static final BooleanQuery EMPTY_QUERY = new BooleanQuery.Builder().build();

    @VisibleForTesting
    final Set<String> elevatedIds;
    private final BooleanQuery includeQuery;
    @VisibleForTesting
    final Map<BytesRef, Integer> priorities;
    private final Set<String> excludedIds;
    private final TermQuery[] excludeQueries;//just keep the term query, b/c we will not always explicitly exclude the item based on markExcludes query time param

    /**
     * Constructs an elevation.
     *
     * @param elevatedIds           The ids of the elevated documents that should appear on top of search results; can be <code>null</code>.
     * @param excludedIds           The ids of the excluded documents that should not appear in search results; can be <code>null</code>.
     * @param indexedValueProvider  Provides the indexed value corresponding to a readable value..
     * @param queryFieldName        The field name to use to create query terms.
     * @param keepElevationPriority Whether to keep the elevation priority order.
     */
    private Elevation(Collection<String> elevatedIds, Collection<String> excludedIds,
                        UnaryOperator<String> indexedValueProvider, String queryFieldName,
                        boolean keepElevationPriority) {
      if (elevatedIds == null || elevatedIds.isEmpty()) {
        this.elevatedIds = Collections.emptySet();
        includeQuery = EMPTY_QUERY;
        priorities = Collections.emptyMap();
      } else {
        ImmutableSet.Builder<String> elevatedIdsBuilder = ImmutableSet.builder();
        BooleanQuery.Builder includeQueryBuilder = new BooleanQuery.Builder();
        ImmutableMap.Builder<BytesRef, Integer> prioritiesBuilder = null;
        if (keepElevationPriority) {
          prioritiesBuilder = ImmutableMap.builder();
        }
        int priorityLevel = elevatedIds.size();
        for (String elevatedId : elevatedIds) {
          elevatedIdsBuilder.add(indexedValueProvider.apply(elevatedId));
          TermQuery tq = new TermQuery(new Term(queryFieldName, elevatedId));
          includeQueryBuilder.add(tq, BooleanClause.Occur.SHOULD);
          if (keepElevationPriority) {
            prioritiesBuilder.put(new BytesRef(elevatedId), priorityLevel--);
          }
        }
        this.elevatedIds = elevatedIdsBuilder.build();
        includeQuery = includeQueryBuilder.build();
        priorities = keepElevationPriority ? prioritiesBuilder.build() : null;
      }

      if (excludedIds == null || excludedIds.isEmpty()) {
        this.excludedIds = Collections.emptySet();
        excludeQueries = null;
      } else {
        ImmutableSet.Builder<String> excludedIdsBuilder = ImmutableSet.builder();
        List<TermQuery> excludeQueriesBuilder = new ArrayList<>(excludedIds.size());
        for (String excludedId : excludedIds) {
          excludedIdsBuilder.add(indexedValueProvider.apply(excludedId));
          excludeQueriesBuilder.add(new TermQuery(new Term(queryFieldName, excludedId)));
        }
        this.excludedIds = excludedIdsBuilder.build();
        excludeQueries = excludeQueriesBuilder.toArray(new TermQuery[excludeQueriesBuilder.size()]);
      }
    }

    @Override
    public String toString() {
      return "{elevatedIds=" + elevatedIds + ", excludedIds=" + excludedIds + "}";
    }
  }

  private class ElevationComparatorSource extends FieldComparatorSource {

    private final Elevation elevation;
    private final SentinelIntSet ordSet; //the key half of the map
    private final BytesRef[] termValues; //the value half of the map

    private ElevationComparatorSource(Elevation elevation) {
      this.elevation = elevation;
      int size = elevation.elevatedIds.size();
      ordSet = new SentinelIntSet(size, -1);
      termValues = keepElevationPriority ? new BytesRef[ordSet.keys.length] : null;
    }

    @Override
    public FieldComparator<Integer> newComparator(String fieldName, final int numHits, int sortPos, boolean reversed) {
      return new SimpleFieldComparator<Integer>() {
        final int[] values = new int[numHits];
        int bottomVal;
        int topVal;
        PostingsEnum postingsEnum;
        final Set<String> seen = new HashSet<>(elevation.elevatedIds.size());

        @Override
        public int compare(int slot1, int slot2) {
          return values[slot1] - values[slot2];  // values will be small enough that there is no overflow concern
        }

        @Override
        public void setBottom(int slot) {
          bottomVal = values[slot];
        }

        @Override
        public void setTopValue(Integer value) {
          topVal = value;
        }

        private int docVal(int doc) {
          if (ordSet.size() > 0) {
            int slot = ordSet.find(doc);
            if (slot >= 0) {
              if (!keepElevationPriority)
                return 1;
              BytesRef id = termValues[slot];
              return elevation.priorities.getOrDefault(id, 0);
            }
          }
          return 0;
        }

        @Override
        public int compareBottom(int doc) {
          return bottomVal - docVal(doc);
        }

        @Override
        public void copy(int slot, int doc) {
          values[slot] = docVal(doc);
        }

        @Override
        protected void doSetNextReader(LeafReaderContext context) throws IOException {
          //convert the ids to Lucene doc ids, the ordSet and termValues needs to be the same size as the number of elevation docs we have
          ordSet.clear();
          Terms terms = context.reader().terms(uniqueKeyFieldName);
          if (terms == null) return;
          TermsEnum termsEnum = terms.iterator();
          BytesRefBuilder term = new BytesRefBuilder();
          Bits liveDocs = context.reader().getLiveDocs();

          for (String id : elevation.elevatedIds) {
            term.copyChars(id);
            if (seen.contains(id) == false && termsEnum.seekExact(term.get())) {
              postingsEnum = termsEnum.postings(postingsEnum, PostingsEnum.NONE);
              int docId = postingsEnum.nextDoc();
              while (docId != DocIdSetIterator.NO_MORE_DOCS && liveDocs != null && !liveDocs.get(docId)) {
                docId = postingsEnum.nextDoc();
              }
              if (docId == DocIdSetIterator.NO_MORE_DOCS ) continue;  // must have been deleted
              int slot = ordSet.put(docId);
              if (keepElevationPriority) {
                termValues[slot] = term.toBytesRef();
              }
              seen.add(id);
              assert postingsEnum.nextDoc() == DocIdSetIterator.NO_MORE_DOCS;
            }
          }
        }

        @Override
        public Integer value(int slot) {
          return values[slot];
        }

        @Override
        public int compareTop(int doc) {
          final int docValue = docVal(doc);
          return topVal - docValue;  // values will be small enough that there is no overflow concern
        }
      };
    }
  }
}
