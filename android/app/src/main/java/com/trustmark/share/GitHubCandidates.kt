package com.trustmark.share

import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubLinkParser
import com.yunx.app.data.network.GitHubLinkType
import java.net.URI
import java.net.URLEncoder

object GitHubCandidates {
    fun recognizes(raw: String) = runCatching { URI(raw).host.lowercase() in setOf("github.com", "raw.githubusercontent.com") }.getOrDefault(false)
    suspend fun list(raw: String, sizes: Set<Long>): List<RemoteCandidate> {
        return when (val parsed = GitHubLinkParser.parse(raw)) {
            is GitHubLinkType.DirectFile -> listOf(RemoteCandidate(parsed.url))
            is GitHubLinkType.Repository -> {
                val api=GitHubApi()
                val repo=api.getRepo(parsed.owner,parsed.repo) ?: error("GitHub 仓库不可访问或请求次数已达上限")
                val sub=parsed.subPath.orEmpty()
                if(sub.startsWith("releases")) {
                    val tag=sub.removePrefix("releases/tag/").takeIf{sub.startsWith("releases/tag/")}
                    val releases=api.getReleases(parsed.owner,parsed.repo) ?: error("GitHub Release 无法获取")
                    releases.filter{!it.draft&&(tag==null||it.tagName==tag)}.flatMap{it.assets}
                        .filter{it.size in sizes}.map{RemoteCandidate(it.downloadUrl,size=it.size)}
                } else {
                    val pathParts=sub.split('/').filter{it.isNotEmpty()}
                    val isSpecific=pathParts.firstOrNull() in setOf("blob","tree")
                    val ref=if(isSpecific)pathParts.getOrNull(1)?:repo.defaultBranch else repo.defaultBranch
                    val requested=if(isSpecific)pathParts.drop(2).joinToString("/") else ""
                    val result=mutableListOf<RemoteCandidate>()
                    val pending=ArrayDeque<Pair<String,String>>()
                    pending.add(ref to "")
                    var scanned=0
                    while(pending.isNotEmpty()) {
                        require(++scanned<=300){"GitHub 目录过多，请填写具体图片所在目录"}
                        val (tree,path)=pending.removeFirst()
                        val entries=api.getTree(parsed.owner,parsed.repo,tree) ?: error("GitHub 目录无法读取")
                        entries.forEach{entry->
                            val full=if(path.isBlank())entry.path else "$path/${entry.path}"
                            val relevant=requested.isBlank()||full==requested||full.startsWith("$requested/")||requested.startsWith("$full/")
                            if(relevant&&entry.type=="tree")pending.add(entry.sha to full)
                            if(relevant&&entry.type=="blob"&&(entry.size==null||entry.size in sizes)) {
                                fun encode(value:String)=URLEncoder.encode(value,"UTF-8").replace("+","%20")
                                val url="https://raw.githubusercontent.com/${parsed.owner}/${parsed.repo}/${encode(ref)}/"+full.split('/').joinToString("/"){encode(it)}
                                result+=RemoteCandidate(url,size=entry.size?:-1)
                            }
                        }
                    }
                    result
                }
            }
            else -> error("请填写具体的 GitHub 仓库、目录、文件或 Release 链接")
        }
    }
}
