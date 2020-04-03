package updatetool.imdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.tinylog.Logger;
import org.tinylog.configuration.Configuration;
import updatetool.Main;
import updatetool.api.Implementation;
import updatetool.api.JobReport.StatusCode;
import updatetool.common.Capabilities;
import updatetool.common.DatabaseSupport;
import updatetool.common.DatabaseSupport.Library;
import updatetool.common.KeyValueStore;
import updatetool.common.SqliteDatabaseProvider;
import updatetool.common.State;
import updatetool.common.Utility;
import updatetool.exceptions.ApiCallFailedException;
import updatetool.exceptions.ImdbDatasetAcquireException;
import updatetool.imdb.ImdbPipeline.ImdbPipelineConfiguration;

public class ImdbDockerImplementation implements Implementation {
    private static final Set<Long> IGNORE_LIBRARIES = new HashSet<>();
    
    public int RUN_EVERY_N_HOUR = 12;
    private String apikeyTmdb, apiauthTvdb;
    private Path plexdata;

    @Override
    public void invoke(String[] args) throws Exception {
        apikeyTmdb = System.getenv("TMDB_API_KEY");
        String tvdbAuthLegacy = System.getenv("TVDB_AUTH_STRING");
        String tvdbApiKey = System.getenv("TVDB_API_KEY");
        String data = System.getenv("PLEX_DATA_DIR");
        String ignore = System.getenv("IGNORE_LIBS");
        String capabilitiesEnv = System.getenv("CAPABILITIES");
        
        EnumSet<Capabilities> capabilities = EnumSet.allOf(Capabilities.class);
        final List<Capabilities> parsed = new ArrayList<>();

        if(capabilitiesEnv != null && !capabilitiesEnv.isBlank()) {
            try {
                parsed.addAll(Arrays
                        .stream(capabilitiesEnv.split(";"))
                        .map(Capabilities::valueOf)
                        .collect(Collectors.toList()));
            } catch(IllegalArgumentException e) {
                String[] msg = e.getMessage().split("\\.");
                Logger.error("Invalid CAPABILITIES value: " + msg[msg.length-1]);
                System.exit(-1);
            }
        }
        
        if(!parsed.isEmpty()) {
            capabilities.removeAll(Capabilities
                    .getUserFlags()
                    .stream()
                    .filter(f -> !parsed.contains(f))
                    .collect(Collectors.toList())
                    );
        } else {
            capabilities.removeAll(Capabilities.getUserFlags());
        }

        Objects.requireNonNull(data, "Environment variable PLEX_DATA_DIR is not set");

        var levels = List.of("trace", "debug", "info", "error", "warn");
        String logging = System.getenv("LOG_LEVEL");

        if(logging != null) {
            logging = logging.toLowerCase();
            if(levels.contains(logging.toLowerCase())) {
                Configuration.set("writer.level", logging);
                Configuration.set("writer2.level", logging);
                System.out.println("Logging level changed to: " + logging);
            } else {
                Logger.warn("Ignoring custom log level. Logging level {} not in allowed levels: {}", logging, levels);
            }
        }

        Logger.info("Running version: {}", Main.version());

        plexdata = Path.of(data);

        if(!Files.exists(plexdata) && !Files.isDirectory(plexdata)) {
            System.err.println("Directory: " + plexdata.toAbsolutePath().toString() + " does not exist.");
            System.exit(-1);
        }

        if(apikeyTmdb == null || apikeyTmdb.isBlank()) {
            Logger.info("No TMDB API key detected. Will not process TMDB backed Movie and TV Series libraries and TMDB orphans.");
            capabilities.remove(Capabilities.TMDB);
        } else {
            Main.testApiTmdb(apikeyTmdb);
            Logger.info("TMDB API key enabled TMDB <=> IMDB matching. Will process TMDB backed Movie and TV Series libraries and TMDB orphans.");
        }
        
        if(tvdbAuthLegacy != null && !tvdbAuthLegacy.isBlank()) {
            Logger.warn("Don't use legacy environment variable TVDB_AUTH_STRING. Use TVDB_API_KEY instead by only providing the TVDB API key.");
            String[] info = tvdbAuthLegacy.split(";");
            if(info.length == 3) {
                tvdbApiKey = info[2];
            } else {
                Logger.error("Invalid TVDB API authorization string given. Must contain 3 items seperated by a ';'. Will ignore TV Series with the TVDB agent.");
            }
        }
        
        if(tvdbApiKey == null || tvdbApiKey.isBlank()) {
            Logger.info("No TVDB API authorization string detected. Will process TVDB backed TV Series libraries.");
            capabilities.remove(Capabilities.TVDB);
        } else {
            Main.testApiTvdb(tvdbApiKey);
            apiauthTvdb = tvdbApiKey;
            Logger.info("TVDB API authorization enabled IMDB rating update for TV Series with the TVDB agent.");
        }

        if(args.length >= 2) {
            RUN_EVERY_N_HOUR = parseCommandInt(args[1], i -> i > 0, "Invalid parameter for: RUN_EVERY_N_HOUR (must be number and > 0)");
        }

        Logger.info("Starting IMDB Watchdog");
        Logger.info("Plex data dir: " + plexdata.toAbsolutePath().toString());
        Logger.info("Invoke every " + RUN_EVERY_N_HOUR + " hour(s)");

        var state = State.recoverImdb(Main.STATE_IMDB);
        var caches = Map.of("tmdb", KeyValueStore.of(Main.PWD.resolve("cache-tmdb2imdb.json")), 
                            "tmdb-series", KeyValueStore.of(Main.PWD.resolve("cache-tmdbseries2imdb.json")),
                            "tmdb-series-blacklist", KeyValueStore.of(Main.PWD.resolve("cache-tmdbseriesBlacklist.json")),
                            "tvdb", KeyValueStore.of(Main.PWD.resolve("cache-tvdb2imdb.json")),
                            "tvdb-blacklist", KeyValueStore.of(Main.PWD.resolve("cache-tvdbBlacklist.json")));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                caches.values().forEach(KeyValueStore::dump);
            } catch (Exception e) {
                Logger.error("Failed to save cache.");
                Logger.error(e);
            }
        }));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                State.dump(Main.STATE_IMDB, state);
            } catch (IOException e) {
                Logger.error("Failed to save state.");
                Logger.error(e);
            }
        }));

        if(!state.isEmpty())
            Logger.info("Loaded " + state.size() + " unfinished job(s).\n");
        
        if(ignore != null && !ignore.isBlank()) {
            String[] candidates = ignore.split(";");
            for(var s : candidates) {
                try {
                    long i = Long.parseLong(s);
                    IGNORE_LIBRARIES.add(i);
                    Logger.info("Ignoring library with ID: {}", i);
                } catch(NumberFormatException e) {}
            }
        }

        Logger.info("Capabilities: " + capabilities.toString());
        
        var dbLocation = plexdata.resolve("Plug-in Support/Databases/com.plexapp.plugins.library.db").toAbsolutePath().toString();
        var config = new ImdbPipelineConfiguration(apikeyTmdb, apiauthTvdb, plexdata.resolve("Metadata/Movies"), dbLocation, capabilities);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        Logger.info("Running first task...");
        scheduler.schedule(new ImdbBatchJob(Main.EXECUTOR, config, plexdata, caches, state, capabilities), 1, TimeUnit.SECONDS);
        Logger.info("Scheduling next tasks to run @ every " + RUN_EVERY_N_HOUR + " hour(s)");
        scheduler.scheduleAtFixedRate(new ImdbBatchJob(Main.EXECUTOR, config, plexdata, caches, state, capabilities), 1, RUN_EVERY_N_HOUR, TimeUnit.HOURS);
    }

    private static class ImdbBatchJob implements Runnable {
        private final ImdbPipelineConfiguration config;
        private final ExecutorService service;
        private final Map<String, KeyValueStore> caches;
        private final Set<ImdbJob> state;
        private final EnumSet<Capabilities> capabilities;
        
        public ImdbBatchJob(ExecutorService service, ImdbPipelineConfiguration config, Path plexdata, Map<String, KeyValueStore> caches, Set<ImdbJob> state, EnumSet<Capabilities> capabilities) {
            this.service = service;
            this.config = config;
            this.caches = caches;
            this.state = state;
            this.capabilities = capabilities;
        }

        @Override
        public void run() {
            try { Main.rollingLogPurge(); } catch (IOException e) { Logger.error(e); }
            
            KeyValueStore.expiredCheck(14, caches.get("tvdb-blacklist"));
            KeyValueStore.expiredCheck(14, caches.get("tmdb-series-blacklist"));
            
            List<Library> libraries = new ArrayList<>();
            ImdbLibraryMetadata metadata = null;
            
            try(var connection = new SqliteDatabaseProvider(config.dbLocation)) {
                var support = new DatabaseSupport(connection);
                if(!capabilities.contains(Capabilities.NO_MOVIE))
                    libraries.addAll(support.requestMovieLibraries(capabilities));
                if(!capabilities.contains(Capabilities.NO_TV))
                    libraries.addAll(support.requestSeriesLibraries(capabilities));
                libraries.removeIf(l -> IGNORE_LIBRARIES.contains(l.id));
                metadata = ImdbLibraryMetadata.fetchAll(libraries, new ImdbDatabaseSupport(connection), config); 
            } catch(Exception e) {
                Logger.error(e.getClass().getSimpleName() + " exception encountered...");
                Logger.error("Please contact the maintainer of the application with the stacktrace below if you think this is unwanted behavior.");
                Logger.error("========================================");
                Logger.error(e);
                Logger.error("========================================");
                Logger.error("The application will terminate now.");
                System.exit(-1);
            }
            
            var sorted = new ArrayList<>(IGNORE_LIBRARIES);
            Collections.sort(sorted);
            Logger.info("Library IDs on ignore list: {}", sorted);
            
            if(libraries.isEmpty()) {
                Logger.info("No libraries found. Sleeping until next invocation...");
                return;
            }
            
            try {
                var jobs = new ArrayDeque<ImdbJob>();
                var pipeline = new ImdbPipeline(metadata, service, caches, config, ImdbRatingDatasetFactory.requestSet());
                var runner = new ImdbJobRunner();
                for(var lib : libraries) {
                    jobs.add(new ImdbJob(lib));
                    Logger.info("[{}] {} has {} item(s)", lib.type, lib.name, lib.items);
                }
                while(!jobs.isEmpty()) {
                    var job = jobs.pop();
                    Logger.info("Processing [{}] {} with UUID {} at stage: {}", job.libraryType, job.library, job.uuid, job.stage);
                    var result = runner.run(job, pipeline);
                    Logger.info("Job returned " + result.code + " : " + result.userDefinedMessage);
                    if(result.code == StatusCode.PASS) {
                        Logger.info("Job finished successfully for [{}] {} with UUID {}", job.libraryType, job.library, job.uuid);
                        state.remove(job);
                    }
                    if(result.code == StatusCode.API_ERROR) {
                        Logger.error(result.userDefinedMessage);
                        Logger.error("Original message: {}", result.exception.getMessage());
                        Logger.info("Aborting queue due to failing to fetch data from a called API. Will wait until next invocation.");
                        Logger.info("It is now safe to suspend execution if this tool should not run 24/7.");
                        return;
                    }
                    if(result.code == StatusCode.ERROR) {
                        throw Utility.rethrow(result.exception);
                    }
                }
                caches.values().forEach(KeyValueStore::dump);
                Logger.info("Completed batch successfully. Waiting till next invocation...");
                Logger.info("It is now safe to suspend execution if this tool should not run 24/7.");
            } catch(ApiCallFailedException e) {
                Logger.error("{} encountered with message: {}", e.getClass().getSimpleName(), e.getMessage());
                Logger.info("Aborting queue due to API error. Will wait until next invocation.");
            } catch(ImdbDatasetAcquireException e) {
                Logger.error("Failed to acquire IMDB dataset due to {}.", e.getCause().getClass().getSimpleName());
                Logger.error("Please contact the maintainer of the application with the stacktrace below if you think this is unwanted behavior.");
                Logger.error("========================================");
                Logger.error(e);
                Logger.error("========================================");
                try { Thread.sleep(10); } catch (InterruptedException e1) {} // Let the separate logger thread print the stack trace before printing the info
                Logger.info("Aborting queue due to failing to retrieve the IMDB data set. Will wait until next invocation.");
            } catch(Exception e) {
                Logger.error(e.getClass().getSimpleName() + " exception encountered...");
                Logger.error("Please contact the maintainer of the application with the stacktrace below if you think this is unwanted behavior.");
                Logger.error("========================================");
                Logger.error(e);
                Logger.error("========================================");
                Logger.error("The application will terminate now.");
                System.exit(-1);
            }
        }

    }

    private static int parseCommandInt(String input, Function<Integer, Boolean> validation, String error) {
        int i = 0;
        try {
            i = Integer.parseInt(input);
        } catch( NumberFormatException e) {
            System.err.println(error);
            System.exit(-1);
        }
        if(!validation.apply(i)) {
            System.err.println(error);
            System.exit(-1);
        }
        return i;
    }

}
