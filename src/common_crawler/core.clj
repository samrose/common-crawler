(ns common-crawler.core
  (:gen-class)
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [net.cgrand.enlive-html :as html]
            [clojure.pprint :as pprint])
  (:import [org.jwat.warc WarcReaderFactory WarcRecord]
           [java.io ByteArrayInputStream]))

;; Configuration
(def base-url "https://data.commoncrawl.org")
(def cdx-api-url "https://index.commoncrawl.org/CC-MAIN-2025-05-index")
(def job-keywords #{"job" "career" "position" "vacancy" "employment" "hiring" "open positions"
                    "we're hiring" "join our team" "work with us" "apply now"
                    "full-time" "full time" "part-time" "part time" "remote"})
(def job-sites {"supabase" "https://supabase.com/careers*"
                "supabase-positions" "https://supabase.com/careers#open-positions*"})

(def job-patterns
  {:titles #"(?i)(?:Senior|Staff|Lead|Principal|Chief|Head of|Director|Manager|Engineer|Developer|Architect|CISO|Support|Product|Design|Marketing|Sales|Accountant|Executive|Solution Architect)"
   :locations #"(?i)(?:Remote|EMEA|Americas|APAC|US|EU|ANZ|Worldwide|Global|United States)(?:\s+(?:time\s*zones?)?)?|(?:[A-Z][a-zA-Z\s]+,\s*[A-Z]{2})|(?:Work From Home)"
   :departments #"(?i)(?:Engineering|Growth|Operations|Security|Marketing|Sales|Support|Product|Design|Finance|Legal|HR|Technology|Development)"
   :job-section-markers #"(?i)(?:Open\s+Positions?|Current\s+Openings?|Career\s+Opportunities?|Jobs?|We're\s+Hiring|Join\s+Our\s+Team|Job\s+Details|Apply Now|Quick Apply|Easy Apply)"})

(defn get-warc-record [warc-path offset length]
  ;;"Fetches and processes a WARC record using JWAT-WARC"
  (try
    (let [url (str "https://data.commoncrawl.org/" warc-path)
          _ (println "Fetching WARC from:" url)
          _ (println "Offset:" offset "Length:" length)
          response (http/get url {:headers {"Range" (format "bytes=%d-%d" offset (+ offset length))}
                                :as :byte-array
                                :throw-exceptions false})]
      ;; Accept both 200 and 206 (Partial Content) as success
      (if (contains? #{200 206} (:status response))
        (try
          (let [input-stream (ByteArrayInputStream. (:body response))
                reader (WarcReaderFactory/getReader input-stream)  ;; Use JWAT's WarcReaderFactory
                record (.getNextRecord reader)]
            (when record
              (println "Got WARC record")
              (let [payload-stream (.getPayloadContent record)  ;; Get payload content directly
                    content (when payload-stream
                             (try 
                               (slurp (java.io.InputStreamReader. payload-stream "UTF-8"))
                               (catch Exception e
                                 (println "Error reading content stream:" (.getMessage e))
                                 nil)))]
                (when content
                  {:headers (into {} (map (fn [h] [(.name h) (.value h)]) 
                                        (.getHeaderList record)))
                   :content content}))))
          (catch Exception e
            (println "Error processing WARC content:" (.getMessage e))
            (println "Stack trace:" (with-out-str (. e printStackTrace)))
            nil))
        (do
          (println "Failed to fetch WARC file. Status:" (:status response))
          (println "Response headers:" (pr-str (:headers response)))
          nil)))
    (catch Exception e
      (println "Error fetching WARC file:" (.getMessage e))
      (println "Stack trace:" (with-out-str (. e printStackTrace)))
      nil)))

(defn decode-html-entities [text]
  "Decodes HTML entities in text"
  (-> text
      (str/replace #"&quot;" "\"")
      (str/replace #"&amp;" "&")
      (str/replace #"&lt;" "<")
      (str/replace #"&gt;" ">")
      (str/replace #"&apos;" "'")
      (str/replace #"&#39;" "'")
      (str/replace #"&#x27;" "'")
      (str/replace #"&#x2F;" "/")
      (str/replace #"\u003c" "<")
      (str/replace #"\u003e" ">")
      (str/replace #"\u0026" "&")))

(defn clean-html-content [content]
  "Cleans HTML content by removing tags and decoding entities"
  (-> content
      (str/replace #"<[^>]+>" " ")  ;; Remove HTML tags
      (decode-html-entities)
      (str/replace #"\s+" " ")      ;; Normalize whitespace
      (str/trim)))

(defn extract-json-from-script [content]
  "Extracts JSON data from script tags in HTML content"
  (when-let [script-content (second (re-find #"<script id=\"js_variables\" type=\"application/json\"[^>]*>(.*?)</script>" content))]
    (try 
      (json/read-str script-content :key-fn keyword)
      (catch Exception e
        (println "Error parsing JSON from script:" (.getMessage e))
        nil))))

(defn extract-job-listings [content]
  (if-let [json-data (extract-json-from-script content)]
    ;; Handle ZipRecruiter's JSON format
    (when-let [jobs (:jobList json-data)]
      (->> jobs
           (map (fn [job]
                  (str/join " - " 
                           (remove nil? 
                                  [(get job :Title)
                                   (get job :City)
                                   (get job :FormattedSalary)
                                   (get job :EmploymentType)]))))
           (filter some?)))
    ;; Fall back to HTML parsing for other sites
    (let [decoded-content (decode-html-entities content)
          parsed (html/html-snippet decoded-content)
          job-sections (html/select parsed [#{:div.careers-section 
                                            :div.jobs-section 
                                            :div.openings 
                                            :section.careers 
                                            :div#careers
                                            :div.job_content
                                            :div.job_description
                                            :div.job_details}])
          job-cards (html/select parsed [#{:div.job-card 
                                         :div.position 
                                         :div.opening 
                                         :div.job-listing 
                                         :article.job
                                         :article.job_result
                                         :div.job_listing
                                         :div.job_card}])
          text-content (if (or (seq job-sections) (seq job-cards))
                        (->> (concat job-sections job-cards)
                             (mapcat html/texts)
                             (str/join "\n"))
                        (clean-html-content decoded-content))]
      (->> (str/split text-content #"(?<=\.)(?=\s)|(?<=\n)")
           (map str/trim)
           (remove str/blank?)
           (filter (fn [text]
                    (or 
                     (and (re-find (:titles job-patterns) text)
                          (re-find (:locations job-patterns) text))
                     (re-find (:departments job-patterns) text)
                     (and (re-find #"(?i)(?:years?.experience|requirements?|qualifications?)" text)
                          (re-find (:titles job-patterns) text))
                     (re-find #"(?i)(?:\$\d+(?:,\d+)?(?:\s*-\s*\$\d+(?:,\d+)?)?(?:\s*(?:per|/)\s*(?:year|yr|month|mo|hour|hr|annually))|(?:salary|compensation))" text))))
           (map #(str/replace % #"\s+" " "))
           distinct))))

(defn job-listing? [url content]
  "Determines if a page is likely a job listing"
  (or 
   ;; Check URL patterns
   (some #(.contains (str/lower-case (or url "")) %) 
         ["career" "jobs" "position" "opening" "hire" "join" "work-with-us" "job-posting" "apply"])
   ;; Check content for job-related text
   (when (not-empty content)
     (or 
      ;; Look for job section markers
      (re-find (:job-section-markers job-patterns) content)
      ;; Look for combinations of job patterns
      (and (re-find (:titles job-patterns) content)
           (re-find (:locations job-patterns) content))
      ;; Look for ZipRecruiter-specific patterns
      (re-find #"(?i)(?:posted|job type|employment type|salary range|benefits|apply now)" content)))))

(defn parse-html [content url]
  "Parses HTML content and extracts job listings"
  (let [job-listings (extract-job-listings content)]
    (when (seq job-listings)
      ;; Group related lines together
      (->> job-listings
           (partition-by #(re-find (:departments job-patterns) %))
           (map #(str/join " - " %))))))

(defn process-cdx-response [response results-atom]
  (let [records (if (string? response)
                  (try 
                    (json/read-str response :key-fn keyword)
                    (catch Exception e
                      (println "Error parsing JSON string:" (.getMessage e))
                      []))
                  (if (map? response)
                    [response]
                    response))]
    (println "\nProcessing" (count records) "records")
    (doseq [record records]
      (let [url (:url record)
            offset (Long/parseLong (str (:offset record)))
            length (Long/parseLong (str (:length record)))]
        (when url
          (println "\nURL:" url)
          (if-let [warc-data (get-warc-record (:filename record) offset length)]
            (let [content (:content warc-data)]
              (if content
                (when (job-listing? url content)
                  (when-let [job-info (parse-html content url)]
                    (println "Found" (count job-info) "job listings")
                    (swap! results-atom conj {:url url :details job-info})))
                (println "No content in WARC data")))
            (println "Failed to get WARC data")))))))

(defn search-site [site-key site-url results-atom max-results]
  (let [query-params {"url" site-url
                     "limit" max-results
                     "filter" "!status:404"
                     "output" "json"}
        _ (println "\n=== Searching" site-key "===")
        _ (println "URL pattern:" site-url)
        response (http/get cdx-api-url {:query-params query-params
                                      :throw-exceptions false
                                      :as :json})]
    (if (= 200 (:status response))
      (do
        (println "Got CDX response with" (count (:body response)) "records")
        (process-cdx-response (:body response) results-atom)
        true)
      (do
        (println "API Error for" site-key ":" (:body response))
        (println "Status:" (:status response))
        false))))

(defn search-common-crawl
  [& {:keys [max-results output-file] 
      :or {max-results 100
           output-file "job-listings.edn"}}]
  (let [results-atom (atom [])]
    ;; Search each job site
    (doseq [[site-key site-url] job-sites]
      (search-site site-key site-url results-atom max-results))
    ;; Write results
    (let [results @results-atom]
      (println "Found" (count results) "total job listings")
      (spit output-file (with-out-str (pprint/pprint results)))
      (println "Results written to" output-file)
      results)))

(defn -main [& args]
  (println "Starting Common Crawler job search...")
  (try 
    (search-common-crawl :max-results 100 :output-file "job-listings.edn")
    (catch Exception e
      (println "Error occurred:" (.getMessage e))
      (println "Cause:" (ex-data e)))))
