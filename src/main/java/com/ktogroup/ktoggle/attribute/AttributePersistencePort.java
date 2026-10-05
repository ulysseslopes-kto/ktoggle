package com.ktogroup.ktoggle.attribute;

import java.util.List;
import java.util.Optional;

public interface AttributePersistencePort {

    Optional<Attribute> findByKey(String key);

    List<Attribute> findAll();

    boolean existsByKey(String key);

    Attribute save(Attribute attribute);
}
