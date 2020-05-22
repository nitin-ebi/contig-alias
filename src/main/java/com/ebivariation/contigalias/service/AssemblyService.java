/*
 * Copyright 2020 EMBL - European Bioinformatics Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ebivariation.contigalias.service;

import com.ebivariation.contigalias.entities.AssemblyEntity;
import com.ebivariation.contigalias.entities.ChromosomeEntity;
import com.ebivariation.contigalias.repo.AssemblyRepository;
import org.hibernate.LazyInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AssemblyService {

    private final AssemblyRepository repository;

    private final Logger logger = LoggerFactory.getLogger(AssemblyService.class);

    @Autowired
    public AssemblyService(AssemblyRepository repository) {
        this.repository = repository;
    }

    public Optional<AssemblyEntity> getAssemblyByAccession(String accession) {
        Optional<AssemblyEntity> assembly = repository.dFindAssemblyEntityByAccession(accession);
        assembly.ifPresent(asm -> {
            try {
                List<ChromosomeEntity> chromosomes = asm.getChromosomes();
                if (chromosomes != null && chromosomes.size() > 0) {
                    chromosomes.forEach(chr -> chr.setAssembly(null));
                }
            } catch (LazyInitializationException e) {
                logger.error("LazyInitializationException when getting List<ChromosomeEntity>.");
            }
        });
        return assembly;
    }

    public void insertAssembly(AssemblyEntity entity) {
        boolean b = !isEntityPresent(entity);
        if (b) {
            throw new IllegalArgumentException(
                    "An assembly with the same genbank or refseq accession already exists!");
        }
        repository.save(entity);
    }

    public void deleteAssembly(AssemblyEntity entity) {
        if (isEntityPresent(entity)) {
            repository.delete(entity);
        }
    }

    public boolean isEntityPresent(AssemblyEntity entity) {
        AssemblyEntity existingAssembly = repository.findAssemblyEntityByGenbankOrRefseq(
                entity.getGenbank(), entity.getRefseq());
        return existingAssembly == null;
    }

}
