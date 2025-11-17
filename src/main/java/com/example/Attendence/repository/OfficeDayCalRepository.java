package com.example.Attendence.repository;

import com.example.Attendence.model.LocalSetting;
import com.example.Attendence.model.OfficeDayCal;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OfficeDayCalRepository extends MongoRepository<OfficeDayCal, String> {
    Optional<OfficeDayCal> findByEntryDate(String entryDate);
}
