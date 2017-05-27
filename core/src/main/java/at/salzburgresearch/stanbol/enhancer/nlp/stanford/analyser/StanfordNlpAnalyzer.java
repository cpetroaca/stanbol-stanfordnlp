package at.salzburgresearch.stanbol.enhancer.nlp.stanford.analyser;

import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.MORPHO_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.NER_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.POS_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.DEPENDENCY_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.COREF_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.SENTIMENT_ANNOTATION;
import static org.apache.stanbol.enhancer.nlp.NlpAnnotations.ENTITY_RELATION_ANNOTATION;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

import org.apache.stanbol.enhancer.nlp.coref.CorefFeature;
import org.apache.stanbol.enhancer.nlp.dependency.DependencyRelation;
import org.apache.stanbol.enhancer.nlp.dependency.GrammaticalRelationTag;
import org.apache.stanbol.enhancer.nlp.model.AnalysedText;
import org.apache.stanbol.enhancer.nlp.model.AnalysedTextFactory;
import org.apache.stanbol.enhancer.nlp.model.Chunk;
import org.apache.stanbol.enhancer.nlp.model.Sentence;
import org.apache.stanbol.enhancer.nlp.model.Span;
import org.apache.stanbol.enhancer.nlp.model.Token;
import org.apache.stanbol.enhancer.nlp.model.annotation.Value;
import org.apache.stanbol.enhancer.nlp.model.tag.TagSet;
import org.apache.stanbol.enhancer.nlp.morpho.MorphoFeatures;
import org.apache.stanbol.enhancer.nlp.ner.NerTag;
import org.apache.stanbol.enhancer.nlp.pos.PosTag;
import org.apache.stanbol.enhancer.nlp.relation.EntityRelation;
import org.apache.stanbol.enhancer.servicesapi.Blob;
import org.ejml.simple.SimpleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.salzburgresearch.stanbol.enhancer.nlp.stanford.analyser.sentiment.LinearSentimentClassMapping;
import at.salzburgresearch.stanbol.enhancer.nlp.stanford.analyser.sentiment.SentimentClassMapping;
import at.salzburgresearch.stanbol.enhancer.nlp.stanford.mappings.TagSetRegistry;
import edu.stanford.nlp.dcoref.CorefChain;
import edu.stanford.nlp.dcoref.CorefChain.CorefMention;
import edu.stanford.nlp.dcoref.CorefCoreAnnotations.CorefChainAnnotation;
import edu.stanford.nlp.ie.machinereading.structure.EntityMention;
import edu.stanford.nlp.ie.machinereading.structure.MachineReadingAnnotations;
import edu.stanford.nlp.ie.machinereading.structure.RelationMention;
import edu.stanford.nlp.ling.CoreAnnotations.LemmaAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.NamedEntityTagAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.PartOfSpeechAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.SentencesAnnotation;
import edu.stanford.nlp.ling.CoreAnnotations.TokensAnnotation;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.AnnotationPipeline;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations.BasicDependenciesAnnotation;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;

public class StanfordNlpAnalyzer {
    

    private static final Logger log = LoggerFactory.getLogger(StanfordNlpAnalyzer.class);

    protected final AnalysedTextFactory analysedTextFactory;
    private final ExecutorService executor;

    private TagSetRegistry tagSetRegistry;

    private final Map<String,AnnotationPipeline> pipelines = new HashMap<String,AnnotationPipeline>();
    private Collection<String> supported = Collections.emptyList();
    
    public StanfordNlpAnalyzer(ExecutorService executor, AnalysedTextFactory atf) {
        this.executor = executor;
        this.analysedTextFactory = atf == null ? AnalysedTextFactory.getDefaultInstance() : atf;
        this.tagSetRegistry = TagSetRegistry.getInstance();
    }
    /**
     * Sets the {@link AnnotationPipeline} for a language
     * @param language the language
     * @param pipeline the pipeline
     * @return the old pipeline for this language or <code>null</code> if none
     */
    public AnnotationPipeline setPipeline(String language, AnnotationPipeline pipeline){
        if(language == null || language.isEmpty()){
            throw new IllegalArgumentException("The parsed language MUST NOT be NULL nor empty!");
        }
        if(pipeline == null){
            throw new IllegalArgumentException("The parsed annotation pipeline MUST NOT be NULL!");
        }
        AnnotationPipeline old = pipelines.put(language.toLowerCase(Locale.ROOT), pipeline);
        if(old == null){
            List<String> supported = new ArrayList<String>(pipelines.keySet());
            Collections.sort(supported);
            this.supported = Collections.unmodifiableCollection(supported);
        } //language was already present ... no need to update supported
        return old;
    }
    /**
     * Getter for the Pipeline of a specific language
     * @param lang the language
     * @return the pipeline or <code>null</code> if the parsed language is not
     * supported
     */
    public AnnotationPipeline getPipeline(String lang){
        return pipelines.get(lang);
    }
    /**
     * Checks if the parsed language is supported by this Analyzer
     * @param language the language
     * @return <code>true</code> it texts in the parsed language are supported.
     */
    public boolean isSupported(String language){
        return pipelines.containsKey(language.toLowerCase(Locale.ROOT));
    }
    /**
     * A alphabetical sorted list of supported languages intended to be used
     * for logging.<p>
     * <b>NOTE:</b> This method is not indented to be used to check if a 
     * language is supported. Users should use {@link #isSupported(String)} for
     * that.
     * @return an alphabetical sorted list of supported languages
     */
    public Collection<String> getSupported(){
        return supported;
    }

    public AnalysedText analyse(String lang, Blob blob) throws IOException {
        if(lang == null || lang.isEmpty()){
            throw new IllegalStateException("The parsed Language MUST NOT be NULL nor empty!");
        }
        lang = lang.toLowerCase(Locale.ROOT); //languages are case insensitive
        if(blob == null){
            throw new IllegalStateException("The parsed Blob MUST NOT be NULL!");
        }
        final AnnotationPipeline pipeline = pipelines.get(lang);
        if(pipeline == null){
            throw new IllegalArgumentException("The parsed language '" + lang
                + "'is not supported (supported: " + supported+ ")!");
        }
        // create an empty Annotation just with the given text
        final AnalysedText at = analysedTextFactory.createAnalysedText(blob);
        TagSet<PosTag> posTagSet = tagSetRegistry.getPosTagSet(lang);
        Map<String,PosTag> adhocPosTags = tagSetRegistry.getAdhocPosTagMap(lang);
        TagSet<NerTag> nerTagSet = tagSetRegistry.getNerTagSet(lang);
        Map<String,NerTag> adhocNerTags = tagSetRegistry.getAdhocNerTagMap(lang);
        TagSet<GrammaticalRelationTag> gramRelationTagSet = 
            tagSetRegistry.getGrammaticalRelationTagSet(lang);
        // run all Annotators on this text
        Annotation document;
        try { //process the text using the executor service
            document = executor.submit(new Callable<Annotation>() {

                @Override
                public Annotation call() throws Exception {
                    Annotation document = new Annotation(at.getSpan());
                    pipeline.annotate(document);
                    return document;
                }
                
            }).get(); //and wait for the results
        } catch (InterruptedException e) {
            throw new IllegalStateException("Interupped while processing text",e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException(cause.getClass().getSimpleName() +
                "Exception while procesing an '"+lang+"' language text (message: "
                + cause.getMessage() + ")!",cause);
        }

        // these are all the sentences in this document
        // a CoreMap is essentially a Map that uses class objects as keys and has values with custom types
        List<CoreMap> sentences = document.get(SentencesAnnotation.class);
        
        //lazily initialized when we need to process sentiment annotations
        SentimentClassMapping sentClassMapping = null;
        
        for (CoreMap sentence : sentences) {
            // traversing the words in the current sentence
            // a CoreLabel is a CoreMap with additional token-specific methods
            Token sentStart = null;
            Token sentEnd = null;
            Token nerStart = null;
            Token nerEnd = null;
            NerTag nerTag = null;
            List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);
            SemanticGraph dependencies = sentence.get(BasicDependenciesAnnotation.class);
            int tokenIdxInSentence = 0;
            
            for (CoreLabel token : tokens) {
                if(token.beginPosition() >= token.endPosition()){
                    log.warn("Illegal Token start:{}/end:{} values -> ignored", token.beginPosition(), token.endPosition());
                    continue;
                }
                Token t = at.addToken(token.beginPosition(), token.endPosition());
                // This can be used to ensure that the text indexes are correct
//              String word = token.get(OriginalTextAnnotation.class);
//              String span = t.getSpan();
//              if(!word.equals(span)){
//                  log.warn("word: '{}' != span: '{}'",word,span);
//              }
                if(sentStart == null){
                    sentStart = t;
                }
                sentEnd = t;
                // Process POS annotations
                String pos = token.get(PartOfSpeechAnnotation.class);
                PosTag posTag;
                if(pos != null){
                    posTag = posTagSet != null ? posTagSet.getTag(pos) : null;
                    if(posTag == null){
                        posTag = adhocPosTags.get(pos);
                        if(posTag == null){
                            log.info("Unmapped POS tag '{}' for language {}",pos,lang);
                            posTag = new PosTag(pos);
                            adhocPosTags.put(pos, posTag);
                        }
                    }
                    t.addAnnotation(POS_ANNOTATION, Value.value(posTag));
                } else {
                    posTag = null;
                }
                log.debug(" > '{}' pos: {}",t.getSpan(),posTag);
                // Process NER annotations
                String ne = token.get(NamedEntityTagAnnotation.class);
                //NOTE: '0' is used to indicate that the current token is no 
                //      named entities
                NerTag actNerTag;
                if(ne != null && !"O".equals(ne)){
                    actNerTag = nerTagSet != null ? nerTagSet.getTag(ne) : null;
                    if(actNerTag == null){
                        actNerTag = adhocNerTags.get(ne);
                    }
                    if(actNerTag == null){
                        actNerTag = new NerTag(ne);
                        log.info("Unmapped Ner tag '{}' for language {}",ne,lang);
                        adhocNerTags.put(ne, actNerTag);
                    }
                } else {
                    actNerTag = null;
                }
                if(nerTag != null && !nerTag.equals(actNerTag)){
                    Chunk nerChunk = at.addChunk(nerStart.getStart(), nerEnd.getEnd());
                    nerChunk.addAnnotation(NER_ANNOTATION, Value.value(nerTag));
                    if(log.isDebugEnabled()){
                        log.debug(" - add Named Entity {} | tag: {}",nerChunk.getSpan(), nerTag);
                    }
                    nerTag = null;
                    nerStart = null;
                    nerEnd = null;
                } 
                if(actNerTag != null){
                    if(nerStart == null){
                        nerStart = t;
                    }
                    nerTag = actNerTag;
                    nerEnd = t;
                }
                //Process the Lemma
                String lemma = token.get(LemmaAnnotation.class);
                if(lemma != null && !lemma.equals(t.getSpan())){
                    MorphoFeatures morpho = new MorphoFeatures(lemma);
                    if(posTag != null){
                        morpho.addPos(posTag);
                    }
                    t.addAnnotation(MORPHO_ANNOTATION, Value.value(morpho));
                }
                
                /*
                 * Add dependencies only if the parse annotator is in the
                 * pipeline and we have a grammatical relation {@link TagSet}
                 * for the given language.
                 */
                if (dependencies != null && gramRelationTagSet != null) {
                    addDependencyRelations(tokens, t, at, gramRelationTagSet, dependencies, ++tokenIdxInSentence);
                }
            } //end iterate over tokens in sentence
            //add the Sentence
            Sentence sent = at.addSentence(sentStart.getStart(), sentEnd.getEnd());
            //Sentiment for the Sentence
            String sentimentClass = sentence.get(SentimentCoreAnnotations.ClassName.class);
            Tree sentimentTree = sentence.get(SentimentCoreAnnotations.AnnotatedTree.class);
            if(sentimentTree != null){
                //we can not use the class as we want a double value
                //int sentiment = RNNCoreAnnotations.getPredictedClass(sentimentTree);
                SimpleMatrix predictions = RNNCoreAnnotations.getPredictions(sentimentTree);
                int size = predictions.getNumElements();
                if(sentClassMapping == null){
                    log.debug(" - {} sentiment classes detected", size);
                    sentClassMapping = new LinearSentimentClassMapping(size);
                }
                if(log.isDebugEnabled()){
                    double[] values = new double[size];
                    for(int i=0;i < size;i++){
                        values[i] = predictions.get(i);
                    }
                    log.debug(" - sentiment: {}[classes: {}]", new Object[]{
                            sentimentClass, Arrays.toString(values)});
                }
                //sum up the predictions of the different classes
                double sentimentValue = 0;
                for(int idx = 0 ; idx < size ; idx ++){
                    double idxSent = sentClassMapping.getIndexWeight(idx);
                    if(!Double.isNaN(idxSent)){
                        sentimentValue += predictions.get(idx) * idxSent;
                    } else { //sentiment classes can not be converted to a number
                        sentimentValue = Double.NaN;
                        break;
                    }
                }
                //Annotating sentence with the calculated sentiment value
                if(!Double.isNaN(sentimentValue)){
                    //TODO: provide a better sentiment annotation where we can
                    //      also parse information about the detected class
                    sent.addAnnotation(SENTIMENT_ANNOTATION, Value.value(sentimentValue));
                }
            }
            
            //Add entity relations in sentence span
            List<RelationMention> relations = sentence.get(MachineReadingAnnotations.RelationMentionsAnnotation.class);
            addRelationMentions(tokens, relations, sent);
            
            //clean up the sentence
            sentStart = null;
            sentEnd = null;
            //we might have still an open NER annotation
            if(nerTag != null){
                Chunk nerChunk = at.addChunk(nerStart.getStart(), nerEnd.getEnd());
                nerChunk.addAnnotation(NER_ANNOTATION, Value.value(nerTag));
            }
        }

        // This is the coreference link graph
        // Each chain stores a set of mentions that link to each other,
        // along with a method for getting the most representative mention
        // Both sentence and token offsets start at 1!
        Map<Integer, CorefChain> graph = document.get(CorefChainAnnotation.class);
        if (graph != null) {
            addCorefMentions(graph, at, sentences);
        }

        return at;
    }

    /**
     * Add dependency tree annotations to the current token.
     * 
     * @param tokens - the list of {@link CoreLabel}s in the current sentence.
     * @param currentToken - the current {@link Token} to which the dependency relations will
     * be added.
     * @param at
     * @param relationTagSet - tag set containing {@link GrammaticalRelationTag}s.
     * @param dependencies - the {@link SemanticGraph} containing the dependency tree relations.
     */
    private void addDependencyRelations(List<CoreLabel> tokens, Token currentToken, AnalysedText at,
            TagSet<GrammaticalRelationTag> relationTagSet, SemanticGraph dependencies, int currentTokenIdx) {
        IndexedWord vertex = dependencies.getNodeByIndexSafe(currentTokenIdx);
        if (vertex == null) {
            // Usually the current token is a punctuation mark in this case.
            return;
        }
        
        List<SemanticGraphEdge> edges = new ArrayList<SemanticGraphEdge>();
        edges.addAll(dependencies.incomingEdgeList(vertex));
        edges.addAll(dependencies.outgoingEdgeList(vertex));
        
        for (SemanticGraphEdge edge : edges) {
            int govIndex = edge.getGovernor().index();
            int depIndex = edge.getDependent().index();
            GrammaticalRelation gramRel = edge.getRelation();
            GrammaticalRelationTag gramRelTag = relationTagSet.getTag(gramRel.getShortName());
            if(gramRelTag != null){
	            boolean isDependent = false;
	            Span partner = null;
	            
	            if (govIndex == currentTokenIdx) {
	                CoreLabel dependentLabel = tokens.get(depIndex - 1);
	                partner = at.addToken(dependentLabel.beginPosition(), dependentLabel.endPosition());
	            } else if (depIndex == currentTokenIdx) {
	                isDependent = true;
	                CoreLabel governorLabel = tokens.get(govIndex - 1);
	                partner = at.addToken(governorLabel.beginPosition(), governorLabel.endPosition());
	            }
	            
	            currentToken.addAnnotation(DEPENDENCY_ANNOTATION, 
	                Value.value(new DependencyRelation(gramRelTag, isDependent, partner)));
            } else {
            	log.warn("Missing GrammaticalRelationTag for {}!",gramRel.getShortName());
            }
	    }
        
        // Finally add the root relation if the word has any.
        Collection<IndexedWord> roots = dependencies.getRoots();
        if (roots.contains(vertex)) {
            GrammaticalRelationTag rootRelTag = relationTagSet.getTag("root");
            currentToken.addAnnotation(DEPENDENCY_ANNOTATION, 
                Value.value(new DependencyRelation(rootRelTag, false, null)));
        }
    }
    
    /**
     * Adds annotations for coref mentions to the {@link Span}s in the {@link AnalysedText}
     * 
     * @param graph
     * @param at
     * @param sentences
     */
    private void addCorefMentions(Map<Integer, CorefChain> graph, AnalysedText at, List<CoreMap> sentences) {
        for (Map.Entry<Integer, CorefChain> entry : graph.entrySet()) {
            CorefChain chain = entry.getValue();
            
            CorefMention reprMention = chain.getRepresentativeMention();
            List<CorefMention> mentions = chain.getMentionsInTextualOrder();
            
            /*
             * We don't care about chains with only 1 mention because those contain
             * only the representative mention without any other mention in the text.
             */
            if (mentions.size() < 2) {
                continue;
            }
            
            for (CorefMention mention : mentions) {
                Span mentionedSpan = getSpanFromMention(at, sentences, mention);
                Set<Span> mentionsAsSpans = new HashSet<Span>();
                boolean isRepresentative = mention.equals(reprMention);
                
                for (CorefMention otherMention : mentions) {
                    if (!otherMention.equals(mention)) {
                        mentionsAsSpans.add(getSpanFromMention(at, sentences, otherMention));
                    }
                }
                
                mentionedSpan.addAnnotation(COREF_ANNOTATION, 
                    Value.value(new CorefFeature(isRepresentative, mentionsAsSpans)));
            }
        }
    }
    
    /**
     * Adds Relation elements between two or more entities
     * @param tokens
     * @param relations
     * @param sentence
     */
    private void addRelationMentions(List<CoreLabel> tokens, List<RelationMention> relations, Sentence sentence) {
    	for (RelationMention relation : relations) {
    		String type = relation.getType();
    		double confidence = relation.getTypeProbabilities().getCount(type);
    		
    		//We're not interested in non-relations
    		if (type.equals("_NR")) continue;
    		
    		Set<Span> entities = new HashSet<Span>();
    		for (EntityMention entity : relation.getEntityMentionArgs()) {
    			int spanStart = -1;
    			int spanEnd = -1;
    			boolean isChunk = false;
    			
    			for (int i = entity.getExtentTokenStart(); i < entity.getExtentTokenEnd(); i ++){
    				CoreLabel token = tokens.get(i);
    				if (spanStart == -1) spanStart = token.beginPosition();
    				spanEnd = token.endPosition();
    				
    				if (i > entity.getExtentTokenStart()) isChunk = true;
			    }
    			
    			if (isChunk) {
    				entities.add(sentence.addChunk(spanStart, spanEnd));
    			} else {
    				entities.add(sentence.addToken(spanStart, spanEnd));
    			}
    		}
    		
    		sentence.addAnnotation(ENTITY_RELATION_ANNOTATION, 
                    Value.value(new EntityRelation(type, confidence, entities)));
    	}
    }
    
    /**
     * Returns the {@link Span} in the {@link AnalysedText} which corresponds
     * to the given {@link CorefMention}
     * 
     * @param at
     * @param sentences
     * @param mention
     * @return
     */
    private Span getSpanFromMention(AnalysedText at, List<CoreMap> sentences, CorefMention mention) {
        CoreMap sentence = sentences.get(mention.sentNum - 1);
        List<CoreLabel> tokens = sentence.get(TokensAnnotation.class);
        
        if (mention.endIndex - mention.startIndex > 1) {
            CoreLabel startToken = tokens.get(mention.startIndex - 1);
            CoreLabel endToken = tokens.get(mention.endIndex - 2);
            
            return at.addChunk(startToken.beginPosition(), endToken.endPosition());
        } else {
            CoreLabel token = tokens.get(mention.startIndex - 1);
            
            return at.addToken(token.beginPosition(), token.endPosition());
        }
    }
}
