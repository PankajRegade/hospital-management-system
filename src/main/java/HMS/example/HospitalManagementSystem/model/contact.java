package HMS.example.HospitalManagementSystem.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

@Entity
public class contact {

	@Id
	String name;
	String email;
	String phone;
	String message;
	public contact() {
		super();
		// TODO Auto-generated constructor stub
	}
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getEmail() {
		return email;
	}
	public void setEmail(String email) {
		this.email = email;
	}
	public String getPhone() {
		return phone;
	}
	public void setPhone(String phone) {
		this.phone = phone;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public contact(String name, String email, String phone, String message) {
		super();
		this.name = name;
		this.email = email;
		this.phone = phone;
		this.message = message;
	}
	@Override
	public String toString() {
		return "contact [name=" + name + ", email=" + email + ", phone=" + phone + ", message=" + message + "]";
	}
	
}
