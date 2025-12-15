package HMS.example.HospitalManagmentSystem.service;


import org.springframework.stereotype.Service;

import HMS.example.HospitalManagmentSystem.model.Doctor;
import HMS.example.HospitalManagmentSystem.repository.DoctorRepository;

import java.util.List;

@Service
public class DoctorService {

    private final DoctorRepository repo;

    public DoctorService(DoctorRepository repo) {
        this.repo = repo;
    }

    public List<Doctor> getAll() { return repo.findAll(); }

    public Doctor getById(Long id) { return repo.findById(id).orElse(null); }

    public void save(Doctor d) { repo.save(d); }

    public void delete(Long id) { repo.deleteById(id); }
}
