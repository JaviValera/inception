/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.kb.reification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.query.Binding;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.tudarmstadt.ukp.inception.kb.InceptionValueMapper;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.graph.KBQualifier;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;

public class NoReification implements ReificationStrategy {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final KnowledgeBaseService kbService;

    public NoReification(KnowledgeBaseService aKbService)
    {
        kbService = aKbService;
    }

    @Override
    public List<Statement> reify(KnowledgeBase kb, KBStatement aStatement) {
        KBHandle instance = aStatement.getInstance();
        KBHandle property = aStatement.getProperty();

        ValueFactory vf = SimpleValueFactory.getInstance();
        IRI subject = vf.createIRI(instance.getIdentifier());
        IRI predicate = vf.createIRI(property.getIdentifier());

        InceptionValueMapper mapper = new InceptionValueMapper();
        Value value = mapper.mapStatementValue(aStatement, vf);

        Statement statement = vf.createStatement(subject, predicate, value);
        List<Statement> statements = new ArrayList<>(1);
        statements.add(statement);
        return statements;
    }

    @Override
    public List<KBStatement> listStatements(KnowledgeBase kb, KBHandle aInstance, boolean aAll)
    {
        Map<String, KBHandle> props = new HashMap<>();
        for (KBHandle prop : kbService.listProperties(kb, aAll)) {
            props.put(prop.getIdentifier(), prop);
        }

        List<Statement> explicitStmts = listStatementsForInstance(kb, aInstance.getIdentifier(),
            false);
        List<Statement> allStmts = listStatementsForInstance(kb, aInstance.getIdentifier(), true);

        List<KBStatement> result = new ArrayList<>();
        for (Statement stmt : allStmts) {
            // Can this really happen?

            Value value = stmt.getObject();
            if (value == null) {
                log.warn("Property with null value detected.");
                continue;
            }

            if (value instanceof BNode) {
                log.warn("Properties with blank node values are not supported");
                continue;
            }

            KBHandle property = props.get(stmt.getPredicate().stringValue());
            if (property == null) {
                // This happens in particular for built-in properties such as
                // RDF / RDFS / OWL properties
                if (aAll) {
                    property = new KBHandle();
                    property.setIdentifier(stmt.getPredicate().stringValue());
                }
                else {
                    continue;
                }
            }

            List<Statement> originalStatements = new ArrayList<>();
            originalStatements.add(stmt);

            KBStatement kbStatement = new KBStatement(aInstance, property, value);
            kbStatement.setInferred(!explicitStmts.contains(stmt));
            kbStatement.setOriginalStatements(originalStatements);

            result.add(kbStatement);
        }

        return result;
    }

    /**
     * Returns all statements for which the given instance identifier is the subject
     */
    private List<Statement> listStatementsForInstance(KnowledgeBase kb, String aInstanceIdentifier,
        boolean aIncludeInferred)
    {
        try (RepositoryConnection conn = kbService.getConnection(kb)) {
            ValueFactory vf = conn.getValueFactory();
            String QUERY = "SELECT * WHERE { ?s ?p ?o . }";
            TupleQuery tupleQuery = conn.prepareTupleQuery(QueryLanguage.SPARQL, QUERY);
            tupleQuery.setBinding("s", vf.createIRI(aInstanceIdentifier));
            tupleQuery.setIncludeInferred(aIncludeInferred);

            TupleQueryResult result;
            try {
                result = tupleQuery.evaluate();
            }
            catch (QueryEvaluationException e) {
                log.warn("Listing statements failed.", e);
                return Collections.emptyList();
            }

            List<Statement> statements = new ArrayList<>();
            IRI subject = vf.createIRI(aInstanceIdentifier);
            while (result.hasNext()) {
                BindingSet bindings = result.next();
                Binding pred = bindings.getBinding("p");
                Binding obj = bindings.getBinding("o");

                IRI predicate = vf.createIRI(pred.getValue().stringValue());
                Statement stmt = vf.createStatement(subject, predicate, obj.getValue());
                statements.add(stmt);
            }
            return statements;
        }
    }

    @Override
    public void deleteStatement(KnowledgeBase kb, KBStatement aStatement)
    {
        kbService.update(kb, (conn) -> {
            conn.remove(aStatement.getOriginalStatements());
            aStatement.setOriginalStatements(Collections.emptyList());
            return null;
        });
    }

    @Override
    public void upsertStatement(KnowledgeBase kb, KBStatement aStatement)
    {
        kbService.update(kb, (conn) -> {
            if (!aStatement.isInferred()) {
                conn.remove(aStatement.getOriginalStatements());
            }
            List<Statement> statements = reify(kb, aStatement);
            conn.add(statements);
            aStatement.setOriginalStatements(statements);

            return null;
        });
    }

    @Override
    public void addQualifier(KnowledgeBase kb, KBQualifier newQualifier)
    {
        log.error("Qualifiers are not supported.");
    }

    @Override
    public void deleteQualifier(KnowledgeBase kb, KBQualifier oldQualifier)
    {
        log.error("Qualifiers are not supported.");
    }

    @Override
    public void upsertQualifier(KnowledgeBase kb, KBQualifier aQualifier)
    {
        log.error("Qualifiers are not supported.");
    }

    @Override
    public List<KBQualifier> listQualifiers(KnowledgeBase kb, KBStatement aStatement)
    {
        log.error("Qualifiers are not supported.");
        return Collections.emptyList();
    }

}