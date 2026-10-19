output "kv_node_public_ips" {
  description = "Public IP addresses of KV nodes"
  value       = aws_instance.kv_nodes[*].public_ip
}

output "kv_node_private_ips" {
  description = "Private IP addresses of KV nodes"
  value       = aws_instance.kv_nodes[*].private_ip
}

output "kv_node_urls" {
  description = "HTTP URLs for direct KV node access"
  value       = [for ip in aws_instance.kv_nodes[*].public_ip : "http://${ip}:8080"]
}

output "leader_url" {
  description = "Leader URL for Leader-Follower writes"
  value       = "http://${aws_instance.kv_nodes[0].public_ip}:8080"
}

output "load_tester_public_ip" {
  description = "Public IP of load tester instance"
  value       = aws_instance.load_tester.public_ip
}

output "leaderless_alb_dns_name" {
  description = "DNS name for the leaderless ALB endpoint"
  value       = aws_lb.leaderless_alb.dns_name
}

output "leaderless_alb_url" {
  description = "HTTP URL for the leaderless ALB endpoint"
  value       = "http://${aws_lb.leaderless_alb.dns_name}"
}
